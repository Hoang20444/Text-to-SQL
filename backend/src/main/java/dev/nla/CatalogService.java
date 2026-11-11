package dev.nla;

import dev.nla.warehouse.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Reads physical metadata from a registered warehouse through its engine adapter. */
@Service
public class CatalogService {
  private final Store store;
  private final Adapters adapters;

  private record Snapshot(long at, List<Map<String, Object>> tables) {}

  private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

  public CatalogService(Store store, Adapters adapters) {
    this.store = store;
    this.adapters = adapters;
  }

  public List<Map<String, Object>> discover(String warehouseId, boolean fresh) {
    Map<String, Object> w = store.warehouse(warehouseId);
    if (Json.str(w, "status").equals("DISABLED"))
      throw new ApiException(
          HttpStatus.CONFLICT, "DATASET_UNAVAILABLE", "Warehouse đã bị vô hiệu hóa.");
    Snapshot cached = snapshots.get(warehouseId);
    if (!fresh && cached != null && System.currentTimeMillis() - cached.at < 15000)
      return cached.tables;
    var adapter = adapters.get(w);
    try (Connection connection =
        adapter.connect(w, adapters.password(store.credential(warehouseId)))) {
      Set<String> schemas = new HashSet<>(adapter.discoverSchemas(connection));
      var tables = adapter.discoverTables(connection, schemas);
      snapshots.put(warehouseId, new Snapshot(System.currentTimeMillis(), tables));
      return tables;
    } catch (SQLException e) {
      org.slf4j.LoggerFactory.getLogger(CatalogService.class)
          .warn(
              "Metadata failure engine={} SQLState={} vendorCode={} type={}",
              w.get("engine"),
              e.getSQLState(),
              e.getErrorCode(),
              e.getClass().getSimpleName());
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "WAREHOUSE_CONNECTION_ERROR",
          "Không kết nối được warehouse hoặc không đọc được metadata.");
    }
  }

  /** Physical tables of a dataset, limited to its allowlist; marks drift as INVALID_SCHEMA. */
  public List<Map<String, Object>> tables(String datasetId) {
    var d = store.dataset(datasetId);
    Set<String> allowed = new HashSet<>();
    Json.objects(d.get("allowedTables")).forEach(t -> allowed.add(key(t)));
    var list =
        discover(Json.str(d, "warehouseId"), false).stream()
            .filter(t -> allowed.contains(key(t)))
            .toList();
    if (list.size() != allowed.size()) {
      d.put("status", "INVALID_SCHEMA");
      store.updateDataset(d, store.semantic(datasetId));
      throw new ApiException(
          HttpStatus.CONFLICT,
          "SCHEMA_MISMATCH",
          "Bảng được cấu hình không còn tồn tại trong warehouse.");
    }
    return list;
  }

  /**
   * Semantic context sent to the AI service: the dataset's semantic model plus its live physical
   * tables and the joins between allowed tables (discovered foreign keys and declared joins).
   */
  public Map<String, Object> context(String datasetId) {
    var sem = new LinkedHashMap<>(store.semantic(datasetId));
    var physical = tables(datasetId);
    Set<String> allowed = new HashSet<>();
    physical.forEach(t -> allowed.add(key(t)));
    Map<String, Map<String, Object>> joins = new LinkedHashMap<>();
    physical.stream()
        .flatMap(t -> Json.objects(t.get("foreignKeys")).stream())
        .filter(rel -> allowed.contains(key(Json.map(Json.map(rel.get("to")).get("table")))))
        .forEach(rel -> joins.put(relationIdentity(rel), rel));
    for (var rel : Json.objects(sem.get("relationships"))) {
      if (allowed.contains(key(Json.map(Json.map(rel.get("from")).get("table"))))
          && allowed.contains(key(Json.map(Json.map(rel.get("to")).get("table")))))
        joins.put(relationIdentity(rel), rel);
    }
    sem.put("tables", physical);
    sem.put("relationships", new ArrayList<>(joins.values()));
    sem.put("referenceTime", java.time.Instant.now().toString());
    return sem;
  }

  private static String relationIdentity(Map<String, Object> relation) {
    var from = Json.map(relation.get("from"));
    var to = Json.map(relation.get("to"));
    String left = key(Json.map(from.get("table"))) + "." + Json.str(from, "column");
    String right = key(Json.map(to.get("table"))) + "." + Json.str(to, "column");
    return left.compareTo(right) < 0 ? left + "|" + right : right + "|" + left;
  }

  static String key(Map<String, Object> table) {
    return Json.str(table, "schema") + "." + Json.str(table, "name");
  }
}
