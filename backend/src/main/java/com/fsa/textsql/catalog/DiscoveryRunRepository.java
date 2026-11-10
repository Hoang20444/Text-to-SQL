package com.fsa.textsql.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsa.textsql.common.ErrorResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DiscoveryRunRepository {

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public DiscoveryRunRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Creates a PENDING run, or returns the run already in progress for the warehouse (a unique
   * partial index allows only one active run per warehouse).
   */
  public CreateResult createPending(String runId, String warehouseId, String requestedBy) {
    try {
      jdbc.sql(
              """
              INSERT INTO discovery_runs (id, warehouse_id, requested_by, status)
              VALUES (:id, :warehouseId, :requestedBy, 'PENDING')
              """)
          .param("id", runId)
          .param("warehouseId", warehouseId)
          .param("requestedBy", requestedBy)
          .update();
      return new CreateResult(find(warehouseId, runId).orElseThrow(), true);
    } catch (DuplicateKeyException alreadyRunning) {
      return new CreateResult(activeRun(warehouseId).orElseThrow(), false);
    }
  }

  public record CreateResult(DiscoveryRun run, boolean created) {}

  public void markRunning(String runId) {
    jdbc.sql("UPDATE discovery_runs SET status = 'RUNNING' WHERE id = :id AND status = 'PENDING'")
        .param("id", runId)
        .update();
  }

  public void markSuccess(String runId, SchemaSnapshot snapshot, String schemaVersion) {
    jdbc.sql(
            """
            UPDATE discovery_runs SET status = 'SUCCESS', metadata = CAST(:metadata AS jsonb),
              schema_version = :version, table_count = :count, completed_at = now()
            WHERE id = :id
            """)
        .param("metadata", write(snapshot))
        .param("version", schemaVersion)
        .param("count", snapshot.tables().size())
        .param("id", runId)
        .update();
  }

  /** {@code errorCode} is CODE or CODE:REASON (sanitized, no driver message). */
  public void markFailed(String runId, String errorCode) {
    jdbc.sql(
            """
            UPDATE discovery_runs SET status = 'FAILED', error_code = :code, completed_at = now()
            WHERE id = :id AND status IN ('PENDING', 'RUNNING')
            """)
        .param("code", errorCode)
        .param("id", runId)
        .update();
  }

  /** Runs left PENDING/RUNNING by a stopped process can never finish: mark them FAILED. */
  public int failInterrupted() {
    return jdbc.sql(
            """
            UPDATE discovery_runs SET status = 'FAILED', error_code = 'DISCOVERY_INTERRUPTED',
              completed_at = now()
            WHERE status IN ('PENDING', 'RUNNING')
            """)
        .update();
  }

  public Optional<DiscoveryRun> find(String warehouseId, String runId) {
    return jdbc.sql(
            "SELECT id, warehouse_id, status, created_at, completed_at, schema_version, table_count,"
                + " metadata::text AS metadata, error_code FROM discovery_runs"
                + " WHERE id = :id AND warehouse_id = :warehouseId")
        .param("id", runId)
        .param("warehouseId", warehouseId)
        .query(this::map)
        .optional();
  }

  public Optional<SchemaSnapshot> latestSnapshot(String warehouseId) {
    return jdbc.sql(
            """
            SELECT metadata::text FROM discovery_runs
            WHERE warehouse_id = :warehouseId AND status = 'SUCCESS'
            ORDER BY completed_at DESC, id DESC LIMIT 1
            """)
        .param("warehouseId", warehouseId)
        .query(String.class)
        .optional()
        .map(this::readSnapshot);
  }

  private Optional<DiscoveryRun> activeRun(String warehouseId) {
    return jdbc.sql(
            "SELECT id, warehouse_id, status, created_at, completed_at, schema_version, table_count,"
                + " metadata::text AS metadata, error_code FROM discovery_runs"
                + " WHERE warehouse_id = :warehouseId AND status IN ('PENDING', 'RUNNING')")
        .param("warehouseId", warehouseId)
        .query(this::map)
        .optional();
  }

  private DiscoveryRun map(ResultSet rs, int rowNum) throws SQLException {
    String metadata = rs.getString("metadata");
    String errorCode = rs.getString("error_code");
    return new DiscoveryRun(
        rs.getString("id"),
        rs.getString("warehouse_id"),
        rs.getString("status"),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("completed_at")),
        rs.getString("schema_version"),
        (Integer) rs.getObject("table_count"),
        metadata == null ? null : readSnapshot(metadata),
        errorCode == null ? null : error(errorCode));
  }

  /** Rebuilds a sanitized error body from the stored CODE[:REASON]. */
  static ErrorResponse.ErrorBody error(String stored) {
    String[] parts = stored.split(":", 2);
    String code = parts[0];
    Map<String, Object> details = parts.length > 1 ? Map.of("reason", parts[1]) : Map.of();
    String message =
        switch (code) {
          case "WAREHOUSE_CONNECTION_ERROR" -> "Không kết nối được kho dữ liệu để quét cấu trúc.";
          case "DISCOVERY_LIMIT" -> "Kho dữ liệu có quá nhiều bảng để quét.";
          case "DISCOVERY_INTERRUPTED" -> "Lượt quét bị gián đoạn do máy chủ khởi động lại.";
          case "DISCOVERY_BUSY" -> "Hệ thống đang bận quét kho khác, vui lòng thử lại sau.";
          default -> "Không quét được cấu trúc kho dữ liệu.";
        };
    return ErrorResponse.of(code, message, !code.equals("DISCOVERY_LIMIT"), details).error();
  }

  private String write(SchemaSnapshot snapshot) {
    try {
      return json.writeValueAsString(snapshot);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize schema snapshot", e);
    }
  }

  private SchemaSnapshot readSnapshot(String raw) {
    try {
      return json.readValue(raw, SchemaSnapshot.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Corrupt schema snapshot in metadata store", e);
    }
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
