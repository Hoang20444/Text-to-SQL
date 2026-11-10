package com.fsa.textsql.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsa.textsql.common.PageQuery;
import com.fsa.textsql.common.PageResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DatasetRepository {

  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
  private static final TypeReference<List<TableRef>> TABLE_LIST = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public DatasetRepository(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** Inserts the dataset unless a row with the same id exists. Returns true when inserted. */
  public boolean insertIfAbsent(DatasetRegistration d) {
    int rows =
        jdbc.sql(
                """
                INSERT INTO datasets
                  (id, warehouse_id, name, description, status, allowed_schemas, allowed_tables,
                   semantic_version, schema_version)
                VALUES
                  (:id, :warehouseId, :name, :description, :status,
                   CAST(:allowedSchemas AS jsonb), CAST(:allowedTables AS jsonb),
                   :semanticVersion, :schemaVersion)
                ON CONFLICT (id) DO NOTHING
                """)
            .param("id", d.id())
            .param("warehouseId", d.warehouseId())
            .param("name", d.name())
            .param("description", d.description())
            .param("status", d.status().name())
            .param("allowedSchemas", write(d.allowedSchemas()))
            .param("allowedTables", write(d.allowedTables()))
            .param("semanticVersion", d.semanticVersion())
            .param("schemaVersion", d.schemaVersion())
            .update();
    return rows == 1;
  }

  /**
   * Stores the semantic model snapshot only while the dataset still has none, so restarts never
   * overwrite a model changed by an admin.
   */
  public boolean setSemanticModelIfEmpty(String datasetId, String semanticModelJson) {
    return jdbc.sql(
                """
                UPDATE datasets SET semantic_model = CAST(:model AS jsonb), updated_at = now()
                WHERE id = :id AND semantic_model = '{}'::jsonb
                """)
            .param("model", semanticModelJson)
            .param("id", datasetId)
            .update()
        == 1;
  }

  public java.util.Optional<Dataset> findById(String id) {
    return jdbc.sql(
            "SELECT id, warehouse_id, name, description, status, allowed_schemas::text AS schemas,"
                + " allowed_tables::text AS tables, semantic_version, schema_version, updated_at"
                + " FROM datasets WHERE id = :id")
        .param("id", id)
        .query(this::map)
        .optional();
  }

  public List<Dataset> findByWarehouse(String warehouseId) {
    return jdbc.sql(
            "SELECT id, warehouse_id, name, description, status, allowed_schemas::text AS schemas,"
                + " allowed_tables::text AS tables, semantic_version, schema_version, updated_at"
                + " FROM datasets WHERE warehouse_id = :warehouseId ORDER BY id")
        .param("warehouseId", warehouseId)
        .query(this::map)
        .list();
  }

  /** Applies a discovery result: new schema version and ACTIVE / INVALID_SCHEMA status. */
  public void updateSchemaState(String datasetId, String schemaVersion, DatasetStatus status) {
    jdbc.sql(
            """
            UPDATE datasets SET schema_version = :version, status = :status, updated_at = now()
            WHERE id = :id
            """)
        .param("version", schemaVersion)
        .param("status", status.name())
        .param("id", datasetId)
        .update();
  }

  /** Raw semantic model JSON of a dataset ("{}" when not configured). */
  public java.util.Optional<String> semanticModel(String id) {
    return jdbc.sql("SELECT semantic_model::text FROM datasets WHERE id = :id")
        .param("id", id)
        .query(String.class)
        .optional();
  }

  /**
   * Filters: warehouseId, status (validated enum name) and search on name/description.
   *
   * @param accessSubject when not null, only datasets of warehouses granted to this subject
   */
  public PageResponse<Dataset> findPage(PageQuery query, String accessSubject) {
    List<String> where = new ArrayList<>();
    Map<String, Object> params = new HashMap<>();
    if (accessSubject != null) {
      where.add(
          "warehouse_id IN (SELECT warehouse_id FROM warehouse_access"
              + " WHERE subject = :accessSubject)");
      params.put("accessSubject", accessSubject);
    }
    if (query.filters().containsKey("warehouseId")) {
      where.add("warehouse_id = :warehouseId");
      params.put("warehouseId", query.filters().get("warehouseId"));
    }
    if (query.filters().containsKey("status")) {
      where.add("status = :status");
      params.put("status", query.filters().get("status"));
    }
    if (query.filters().containsKey("search")) {
      where.add("(name ILIKE :search ESCAPE '\\' OR description ILIKE :search ESCAPE '\\')");
      params.put("search", PageQuery.likePattern(query.filters().get("search")));
    }
    String whereSql = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);

    long total =
        jdbc.sql("SELECT count(*) FROM datasets" + whereSql)
            .params(params)
            .query(Long.class)
            .single();

    // sortColumn and direction come from PageQuery's allowlist, never from raw input.
    String sql =
        "SELECT id, warehouse_id, name, description, status, allowed_schemas::text AS schemas,"
            + " allowed_tables::text AS tables, semantic_version, schema_version, updated_at"
            + " FROM datasets"
            + whereSql
            + " ORDER BY "
            + query.sortColumn()
            + " "
            + query.direction()
            + ", id "
            + query.direction()
            + " LIMIT :limit OFFSET :offset";
    params.put("limit", query.pageSize());
    params.put("offset", query.offset());
    List<Dataset> items = jdbc.sql(sql).params(params).query(this::map).list();
    return PageResponse.of(items, query, total);
  }

  private Dataset map(ResultSet rs, int rowNum) throws SQLException {
    return new Dataset(
        rs.getString("id"),
        rs.getString("warehouse_id"),
        rs.getString("name"),
        rs.getString("description"),
        DatasetStatus.valueOf(rs.getString("status")),
        read(rs.getString("schemas"), STRING_LIST),
        read(rs.getString("tables"), TABLE_LIST),
        rs.getString("semantic_version"),
        rs.getString("schema_version"),
        rs.getTimestamp("updated_at").toInstant());
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize dataset allowlist", e);
    }
  }

  private <T> T read(String raw, TypeReference<T> type) {
    try {
      return json.readValue(raw, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Corrupt dataset allowlist in metadata store", e);
    }
  }
}
