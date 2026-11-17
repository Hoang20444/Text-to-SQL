package dev.nla.registry;

import dev.nla.common.ApiException;
import dev.nla.common.Json;
import dev.nla.store.Store;
import dev.nla.warehouse.*;
import dev.nla.warehouse.Adapters;
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
          "Không kết nối được warehouse hoặc không đọc được metadata.",
          Json.obj("reason", ConnectionDiagnosis.reason(e)));
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
    var relationships = Json.objects(sem.get("relationships"));
    for (int i = 0; i < relationships.size(); i++) {
      var relation = relationships.get(i);
      String path = "relationships[" + i + "]";
      if (!endpoints.add(relationIdentity(relation)))
        throw invalid("Join bị khai báo lặp hoặc mâu thuẫn.", path);
      if (!Set.of("ONE_TO_ONE", "MANY_TO_ONE", "ONE_TO_MANY", "MANY_TO_MANY")
          .contains(Json.str(relation, "cardinality")))
        throw ApiException.bad("Cardinality không hợp lệ ở " + path + ".cardinality.");
      for (String side : List.of("from", "to")) {
        var endpoint = Json.map(relation.get(side));
        if (!fields
            .getOrDefault(key(Json.map(endpoint.get("table"))), Set.of())
            .contains(Json.str(endpoint, "column")))
          throw invalid("Join tham chiếu cột ngoài dataset.", path + "." + side + ".column");
      }
    }
    var dimensions = Json.objects(sem.get("dimensions"));
    uniqueNames(Json.objects(sem.get("metrics")), dimensions);
    for (int i = 0; i < dimensions.size(); i++) {
      var d = dimensions.get(i);
      if (!fields
          .getOrDefault(key(Json.map(d.get("table"))), Set.of())
          .contains(Json.str(d, "column")))
        throw invalid(
            "Dimension '" + Json.str(d, "name") + "' không khớp metadata vật lý.",
            "dimensions[" + i + "].column");
    }
    Set<String> dimensionNames = new HashSet<>();
    dimensions.forEach(d -> dimensionNames.add(Json.str(d, "name")));
    var metrics = Json.objects(sem.get("metrics"));
    for (int i = 0; i < metrics.size(); i++) {
      var m = metrics.get(i);
      String path = "metrics[" + i + "]";
      String name = Json.str(m, "name");
      if (Json.objects(m.get("tableRefs")).stream().anyMatch(t -> !fields.containsKey(key(t))))
        throw invalid("Metric '" + name + "' tham chiếu bảng ngoài dataset.", path + ".tableRefs");
      Object compatible = m.get("compatibleDimensions");
      if (!(compatible instanceof List<?> names) || !dimensionNames.containsAll(names))
        throw invalid(
            "Metric '" + name + "' có compatibleDimensions không hợp lệ.",
            path + ".compatibleDimensions");
      try {
        net.sf.jsqlparser.parser.CCJSqlParserUtil.parseExpression(Json.str(m, "expression"));
      } catch (Exception e) {
        throw invalid("Biểu thức của metric '" + name + "' không hợp lệ.", path + ".expression");
      }
    }
  }

  /**
   * Queries and AI answers select metrics and dimensions by name, so every name must be present and
   * identify exactly one definition (case-insensitive, shared by metrics and dimensions).
   */
  private static void uniqueNames(
      List<Map<String, Object>> metrics, List<Map<String, Object>> dimensions) {
    Set<String> seen = new HashSet<>();
    for (String kind : List.of("metrics", "dimensions")) {
      var items = kind.equals("metrics") ? metrics : dimensions;
      for (int i = 0; i < items.size(); i++) {
        String name = Json.str(items.get(i), "name").trim();
        String path = kind + "[" + i + "].name";
        if (name.isEmpty()) throw invalid("Tên định nghĩa không được để trống.", path);
        if (!seen.add(name.toLowerCase(Locale.ROOT)))
          throw invalid("Tên '" + name + "' bị trùng với định nghĩa khác.", path);
      }
    }
  }

  /**
   * SEMANTIC_MODEL_INVALID with details.path pointing at the wrong field, e.g.
   * metrics[0].expression.
   */
  private static ApiException invalid(String message, String path) {
    return new ApiException(
        HttpStatus.CONFLICT, "SEMANTIC_MODEL_INVALID", message, Json.obj("path", path));
  }

  /**
   * Keeps only declared joins: GET returns discovered foreign keys together with declarations, so a
   * client that sends the model back must not turn physical keys into stored declarations.
   */
  public List<Map<String, Object>> declaredRelationships(
      List<Map<String, Object>> tables, List<Map<String, Object>> relationships) {
    Set<String> discovered = new HashSet<>();
    tables.stream()
        .flatMap(t -> Json.objects(t.get("foreignKeys")).stream())
        .forEach(rel -> discovered.add(relationIdentity(rel)));
    return relationships.stream()
        .filter(rel -> !discovered.contains(relationIdentity(rel)))
        .toList();
  }

  private static String relationIdentity(Map<String, Object> relation) {
    var from = Json.map(relation.get("from"));
    var to = Json.map(relation.get("to"));
    String left = key(Json.map(from.get("table"))) + "." + Json.str(from, "column");
    String right = key(Json.map(to.get("table"))) + "." + Json.str(to, "column");
    return left.compareTo(right) < 0 ? left + "|" + right : right + "|" + left;
  }

  /** sha256:<16 hex> over tables, columns, types, keys; the same structure gives the same value. */
  public static String schemaVersion(List<Map<String, Object>> tables) {
    var canonical = new TreeMap<String, Object>();
    for (var t : tables) {
      var columns = new TreeMap<String, Object>();
      Json.objects(t.get("columns"))
          .forEach(
              c ->
                  columns.put(
                      Json.str(c, "name"), Json.str(c, "dataType") + ":" + c.get("nullable")));
      canonical.put(
          key(t),
          Json.obj(
              "columns", columns,
              "primaryKey", t.get("primaryKey"),
              "foreignKeys", t.get("foreignKeys")));
    }
    try {
      byte[] hash =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(Json.write(canonical).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return "sha256:" + java.util.HexFormat.of().formatHex(hash).substring(0, 16);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String key(Map<String, Object> table) {
    return Json.str(table, "schema") + "." + Json.str(table, "name");
  }
}
