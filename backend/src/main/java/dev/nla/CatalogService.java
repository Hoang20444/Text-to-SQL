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

  static String key(Map<String, Object> table) {
    return Json.str(table, "schema") + "." + Json.str(table, "name");
  }
}
