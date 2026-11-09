package com.fsa.textsql.query;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Basic query history (T-1.3.2): one row per question, created RUNNING then completed. */
@Repository
public class QueryHistoryRepository {

  private final JdbcClient jdbc;

  public QueryHistoryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public record Started(
      String queryId,
      String correlationId,
      String ownerSubject,
      String warehouseId,
      String datasetId,
      String engine,
      String question,
      String executionMode,
      String semanticVersion,
      String schemaVersion,
      Instant createdAt) {}

  public record Completed(
      String status,
      String model,
      String generatedSql,
      Integer rowCount,
      Boolean truncated,
      long latencyMs,
      String errorCode,
      Instant completedAt) {}

  public void insertRunning(Started q) {
    jdbc.sql(
            """
            INSERT INTO query_history
              (id, correlation_id, owner_subject, warehouse_id, dataset_id, engine, question,
               status, execution_mode, semantic_version, schema_version, created_at)
            VALUES
              (:id, :correlationId, :owner, :warehouseId, :datasetId, :engine, :question,
               'RUNNING', :mode, :semanticVersion, :schemaVersion, :createdAt)
            """)
        .param("id", q.queryId())
        .param("correlationId", q.correlationId())
        .param("owner", q.ownerSubject())
        .param("warehouseId", q.warehouseId())
        .param("datasetId", q.datasetId())
        .param("engine", q.engine())
        .param("question", q.question())
        .param("mode", q.executionMode())
        .param("semanticVersion", q.semanticVersion())
        .param("schemaVersion", q.schemaVersion())
        .param("createdAt", Timestamp.from(q.createdAt()))
        .update();
  }

  public void complete(String queryId, Completed c) {
    jdbc.sql(
            """
            UPDATE query_history SET
              status = :status, model = :model, generated_sql = :sql, row_count = :rowCount,
              truncated = :truncated, latency_ms = :latency, error_code = :errorCode,
              completed_at = :completedAt
            WHERE id = :id AND status = 'RUNNING'
            """)
        .param("status", c.status())
        .param("model", c.model())
        .param("sql", c.generatedSql())
        .param("rowCount", c.rowCount())
        .param("truncated", c.truncated())
        .param("latency", (int) Math.min(Integer.MAX_VALUE, c.latencyMs()))
        .param("errorCode", c.errorCode())
        .param("completedAt", Timestamp.from(c.completedAt()))
        .param("id", queryId)
        .update();
  }
}
