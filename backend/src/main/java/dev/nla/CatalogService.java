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

  /** Data Catalog collections: live tables, joins, or semantic metrics/dimensions/examples. */
  public List<Map<String, Object>> collection(String datasetId, String kind) {
    if (kind.equals("tables")) return tables(datasetId);
    if (kind.equals("relationships")) return Json.objects(context(datasetId).get("relationships"));
    if (!Set.of("metrics", "dimensions", "examples").contains(kind)) throw ApiException.notFound();
    return Json.objects(store.semantic(datasetId).get(kind));
  }

  public Map<String, Object> summary(String id) {
    var d = store.dataset(id);
    var tables = tables(id);
    var metrics = collection(id, "metrics");
    var dims = collection(id, "dimensions");
    var examples = collection(id, "examples");
    return Json.obj(
        "datasetId",
        id,
        "semanticVersion",
        d.get("semanticVersion"),
        "schemaVersion",
        d.get("schemaVersion"),
        "tableCount",
        tables.size(),
        "metricCount",
        metrics.size(),
        "dimensionCount",
        dims.size(),
        "relationshipCount",
        collection(id, "relationships").size(),
        "exampleCount",
        examples.size(),
        "tableCategories",
        categories(tables),
        "metricCategories",
        categories(metrics),
        "exampleCategories",
        categories(examples));
  }

  static List<Map<String, Object>> categories(List<Map<String, Object>> items) {
    var counts = new TreeMap<String, Long>();
    items.stream()
        .map(i -> Json.str(i, "category"))
        .filter(s -> !s.isBlank())
        .forEach(s -> counts.merge(s, 1L, Long::sum));
    return counts.entrySet().stream()
        .map(e -> Json.obj("name", e.getKey(), "count", e.getValue()))
        .toList();
  }

  /** Semantic metadata must reference columns/tables that exist in the dataset's tables. */
  public void validateSemantic(Map<String, Object> sem, List<Map<String, Object>> tables) {
    Map<String, Set<String>> fields = new HashMap<>();
    for (var t : tables) {
      Set<String> columns = new HashSet<>();
      Json.objects(t.get("columns")).forEach(c -> columns.add(Json.str(c, "name")));
      fields.put(key(t), columns);
    }
    Set<String> endpoints = new HashSet<>();
    for (var relation : Json.objects(sem.get("relationships"))) {
      if (!endpoints.add(relationIdentity(relation)))
        throw new ApiException(
            HttpStatus.CONFLICT, "SEMANTIC_MODEL_INVALID", "Join bị khai báo lặp hoặc mâu thuẫn.");
      if (!Set.of("ONE_TO_ONE", "MANY_TO_ONE", "ONE_TO_MANY", "MANY_TO_MANY")
          .contains(Json.str(relation, "cardinality")))
        throw ApiException.bad("Cardinality không hợp lệ.");
      for (String side : List.of("from", "to")) {
        var endpoint = Json.map(relation.get(side));
        if (!fields
            .getOrDefault(key(Json.map(endpoint.get("table"))), Set.of())
            .contains(Json.str(endpoint, "column")))
          throw new ApiException(
              HttpStatus.CONFLICT, "SEMANTIC_MODEL_INVALID", "Join tham chiếu cột ngoài dataset.");
      }
    }
    for (var d : Json.objects(sem.get("dimensions")))
      if (!fields
          .getOrDefault(key(Json.map(d.get("table"))), Set.of())
          .contains(Json.str(d, "column")))
        throw new ApiException(
            HttpStatus.CONFLICT, "SEMANTIC_MODEL_INVALID", "Dimension không khớp metadata vật lý.");
    Set<String> dimensionNames = new HashSet<>();
    Json.objects(sem.get("dimensions")).forEach(d -> dimensionNames.add(Json.str(d, "name")));
    for (var m : Json.objects(sem.get("metrics"))) {
      if (Json.objects(m.get("tableRefs")).stream().anyMatch(t -> !fields.containsKey(key(t))))
        throw new ApiException(
            HttpStatus.CONFLICT, "SEMANTIC_MODEL_INVALID", "Metric tham chiếu bảng ngoài dataset.");
      Object dimensions = m.get("compatibleDimensions");
      if (!(dimensions instanceof List<?> names) || !dimensionNames.containsAll(names))
        throw new ApiException(
            HttpStatus.CONFLICT, "SEMANTIC_MODEL_INVALID", "Compatible dimensions không hợp lệ.");
      try {
        net.sf.jsqlparser.parser.CCJSqlParserUtil.parseExpression(Json.str(m, "expression"));
      } catch (Exception e) {
        throw new ApiException(
            HttpStatus.CONFLICT, "SEMANTIC_MODEL_INVALID", "Biểu thức metric không hợp lệ.");
      }
    }
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
