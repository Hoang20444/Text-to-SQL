package com.fsa.textsql.warehouse;

import com.fsa.textsql.common.PageQuery;
import com.fsa.textsql.common.PageResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WarehouseRepository {

  private final JdbcClient jdbc;

  public WarehouseRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts the warehouse unless a row with the same id exists. Returns true when inserted. */
  public boolean insertIfAbsent(WarehouseRegistration w) {
    int rows =
        jdbc.sql(
                """
                INSERT INTO warehouses
                  (id, name, engine, status, host, port, database_name, default_schema,
                   username, credential_reference)
                VALUES
                  (:id, :name, :engine, :status, :host, :port, :database, :defaultSchema,
                   :username, :credentialReference)
                ON CONFLICT (id) DO NOTHING
                """)
            .param("id", w.id())
            .param("name", w.name())
            .param("engine", w.engine().name())
            .param("status", w.status().name())
            .param("host", w.host())
            .param("port", w.port())
            .param("database", w.database())
            .param("defaultSchema", w.defaultSchema())
            .param("username", w.username())
            .param("credentialReference", w.credentialReference())
            .update();
    return rows == 1;
  }

  public boolean exists(String id) {
    return jdbc.sql("SELECT count(*) FROM warehouses WHERE id = :id")
            .param("id", id)
            .query(Long.class)
            .single()
        > 0;
  }

  /** Filters: engine, status (already validated enum names) and search on name. */
  public PageResponse<WarehouseSummary> findPage(PageQuery query) {
    List<String> where = new ArrayList<>();
    Map<String, Object> params = new HashMap<>();
    if (query.filters().containsKey("engine")) {
      where.add("engine = :engine");
      params.put("engine", query.filters().get("engine"));
    }
    if (query.filters().containsKey("status")) {
      where.add("status = :status");
      params.put("status", query.filters().get("status"));
    }
    if (query.filters().containsKey("search")) {
      where.add("name ILIKE :search ESCAPE '\\'");
      params.put("search", PageQuery.likePattern(query.filters().get("search")));
    }
    String whereSql = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);

    long total =
        jdbc.sql("SELECT count(*) FROM warehouses" + whereSql)
            .params(params)
            .query(Long.class)
            .single();

    // sortColumn and direction come from PageQuery's allowlist, never from raw input.
    String sql =
        "SELECT id, name, engine, status, read_only_query, metadata_discovery FROM warehouses"
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
    List<WarehouseSummary> items =
        jdbc.sql(sql)
            .params(params)
            .query(
                (rs, n) ->
                    new WarehouseSummary(
                        rs.getString("id"),
                        rs.getString("name"),
                        Engine.valueOf(rs.getString("engine")),
                        WarehouseStatus.valueOf(rs.getString("status")),
                        new WarehouseSummary.Capabilities(
                            rs.getBoolean("read_only_query"), rs.getBoolean("metadata_discovery"))))
            .list();
    return PageResponse.of(items, query, total);
  }
}
