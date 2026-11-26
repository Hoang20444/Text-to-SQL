package dev.nla.registry;

import dev.nla.common.ApiException;
import dev.nla.common.Json;
import dev.nla.common.Requests;
import dev.nla.knowledge.KnowledgeService;
import dev.nla.query.SemanticCompiler;
import dev.nla.store.Store;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Semantic model of a dataset (T-3.1.2): the active business definitions (metrics, dimensions,
 * relationships, examples, business rules) together with the live physical tables and versions.
 */
@RestController
@RequestMapping("/api/v1")
public class SemanticModelController {
  private final Store store;
  private final CatalogService catalog;
  private final MetricExpressions metricExpressions;
  private final RelationshipCardinality cardinality;
  private final ExampleQueries exampleQueries;
  private final KnowledgeService knowledge;

  public SemanticModelController(
      Store store,
      CatalogService catalog,
      MetricExpressions metricExpressions,
      RelationshipCardinality cardinality,
      ExampleQueries exampleQueries,
      KnowledgeService knowledge) {
    this.store = store;
    this.catalog = catalog;
    this.metricExpressions = metricExpressions;
    this.cardinality = cardinality;
    this.exampleQueries = exampleQueries;
    this.knowledge = knowledge;
  }

  /** Any user allowed to read the dataset sees the full context; access is checked by Store. */
  @GetMapping("/datasets/{id}/semantic-model")
  Map<String, Object> semanticModel(@PathVariable String id) {
    var model = catalog.context(id);
    // The dataset record owns the active versions; the stored document may be older.
    var dataset = store.dataset(id);
    model.put("datasetId", id);
    model.put("semanticVersion", dataset.get("semanticVersion"));
    model.put("schemaVersion", dataset.get("schemaVersion"));
    return model;
  }

  /**
   * Replaces the active business definitions. The body is the GET document; physical fields
   * (tables, referenceTime, datasetId) are accepted so the UI can send it back, but are ignored
   * because physical metadata always comes from the warehouse.
   */
  @PutMapping("/datasets/{id}/semantic-model")
  @PreAuthorize("hasRole('ADMIN')")
  Map<String, Object> updateSemanticModel(
      @PathVariable String id, @RequestBody Map<String, Object> body) {
    var checked = check(id, body);
    var dataset = checked.dataset();
    dataset.put("semanticVersion", body.get("semanticVersion"));
    dataset.put("updatedAt", Instant.now().toString());
    store.updateDataset(dataset, checked.model());
    store.audit("SEMANTIC_UPDATE", id, Json.obj("semanticVersion", body.get("semanticVersion")));
    // New definitions make the knowledge index stale: sync it in the background (T-4.1.2).
    // Saving the model never fails because of the knowledge base.
    try {
      knowledge.reindex(id, "SEMANTIC_MODEL");
    } catch (RuntimeException failure) {
      org.slf4j.LoggerFactory.getLogger(SemanticModelController.class)
          .warn("Knowledge sync was not started dataset={}", id, failure);
    }
    return semanticModel(id);
  }

  /**
   * Runs every PUT check without saving, so the workbench can show problems before the admin
   * commits a new version. Errors use the same codes and details.path as PUT; a valid model returns
   * counts and non-blocking diagnostics.
   */
  @PostMapping("/datasets/{id}/semantic-model/validate")
  @PreAuthorize("hasRole('ADMIN')")
  Map<String, Object> validateSemanticModel(
      @PathVariable String id, @RequestBody Map<String, Object> body) {
    var model = check(id, body).model();
    var metrics = Json.objects(model.get("metrics"));
    var dimensions = Json.objects(model.get("dimensions"));
    List<String> diagnostics = new ArrayList<>();
    Set<Object> usedDimensions = new HashSet<>();
    for (var metric : metrics) {
      List<?> compatible =
          metric.get("compatibleDimensions") instanceof List<?> list ? list : List.of();
      usedDimensions.addAll(compatible);
      if (compatible.isEmpty())
        diagnostics.add(
            "Metric '" + Json.str(metric, "name") + "' chưa có chiều phân tích tương thích.");
    }
    for (var dimension : dimensions)
      if (!usedDimensions.contains(dimension.get("name")))
        diagnostics.add(
            "Dimension '" + Json.str(dimension, "name") + "' chưa được metric nào sử dụng.");
    return Json.obj(
        "valid",
        true,
        "semanticVersion",
        model.get("semanticVersion"),
        "metricCount",
        metrics.size(),
        "dimensionCount",
        dimensions.size(),
        "relationshipCount",
        Json.objects(model.get("relationships")).size(),
        "diagnostics",
        diagnostics);
  }

  /**
   * Each compatible dimension must be reachable from the metric's table through many-to-one joins
   * (T-3.3.1), using the same rules as semantic-query, so a saved pair never fails or fans out at
   * query time. Metrics measured on several tables are left to the query-time check.
   */
  private static void checkCompatibleDimensions(
      String engine, Map<String, Object> model, List<Map<String, Object>> tables) {
    var context = new LinkedHashMap<>(model);
    List<Map<String, Object>> relationships = new ArrayList<>();
    Set<String> allowed = new HashSet<>();
    tables.forEach(t -> allowed.add(CatalogService.key(t)));
    tables.stream()
        .flatMap(t -> Json.objects(t.get("foreignKeys")).stream())
        .filter(
            rel ->
                allowed.contains(
                    CatalogService.key(Json.map(Json.map(rel.get("to")).get("table")))))
        .forEach(relationships::add);
    relationships.addAll(Json.objects(model.get("relationships")));
    context.put("relationships", relationships);
    // Only reachability matters here; the real dialect quoting is applied at query time.
    var compiler = new SemanticCompiler(engine, identifier -> identifier);
    var metrics = Json.objects(model.get("metrics"));
    for (int i = 0; i < metrics.size(); i++) {
      var metric = metrics.get(i);
      if (Json.objects(metric.get("tableRefs")).size() != 1) continue;
      List<?> compatible =
          metric.get("compatibleDimensions") instanceof List<?> list ? list : List.of();
      for (Object dimension : compatible) {
        try {
          compiler.compile(context, List.of(Json.str(metric, "name")), List.of((String) dimension));
        } catch (ApiException e) {
          throw new ApiException(
              HttpStatus.CONFLICT,
              "SEMANTIC_MODEL_INVALID",
              e.getMessage(),
              Json.obj("path", "metrics[" + i + "].compatibleDimensions"));
        }
      }
    }
  }

  /** The dataset record and the model that would be stored, after every check passed. */
  private record Checked(Map<String, Object> dataset, Map<String, Object> model) {}

  /** All checks shared by PUT and validate; throws on the first problem, stores nothing. */
  private Checked check(String id, Map<String, Object> body) {
    Requests.fields(
        body,
        Set.of(
            "datasetId",
            "tables",
            "referenceTime",
            "schemaVersion",
            "semanticVersion",
            "timezone",
            "metrics",
            "dimensions",
            "relationships",
            "examples",
            "businessRules"));
    Requests.required(body, "semanticVersion", "timezone");
    if (!SemanticVersions.validFormat(body.get("semanticVersion")))
      throw ApiException.bad("semanticVersion phải có dạng MAJOR.MINOR.PATCH, ví dụ 1.2.0.");
    try {
      ZoneId.of(Json.str(body, "timezone"));
    } catch (DateTimeException e) {
      throw ApiException.bad("Timezone không hợp lệ.");
    }
    var dataset = store.dataset(id);
    // A model edited on an older table structure must be reloaded before it can be saved.
    if (body.containsKey("schemaVersion")
        && !Objects.equals(body.get("schemaVersion"), dataset.get("schemaVersion")))
      throw new ApiException(
          HttpStatus.CONFLICT,
          "SCHEMA_MISMATCH",
          "Cấu trúc bảng đã thay đổi; tải lại mô hình ngữ nghĩa trước khi lưu.");
    var tables = catalog.tables(id);
    var model = new LinkedHashMap<String, Object>();
    for (String field : List.of("metrics", "dimensions", "examples", "businessRules"))
      model.put(field, body.get(field) instanceof List<?> list ? list : List.of());
    model.put(
        "relationships",
        catalog.declaredRelationships(tables, Json.objects(body.get("relationships"))));
    model.put("timezone", body.get("timezone"));
    model.put("semanticVersion", body.get("semanticVersion"));
    model.put("schemaVersion", dataset.get("schemaVersion"));
    model.put("tables", List.of());
    catalog.validateSemantic(model, tables);
    var warehouse = store.warehouse(Json.str(dataset, "warehouseId"));
    String engine = Json.str(warehouse, "engine");
    metricExpressions.validate(engine, Json.objects(model.get("metrics")), tables);
    checkCompatibleDimensions(engine, model, tables);
    exampleQueries.validate(engine, model, tables);
    if (SemanticVersions.conflict(dataset.get("semanticVersion"), store.semantic(id), model))
      throw new ApiException(
          HttpStatus.CONFLICT,
          "SEMANTIC_MODEL_INVALID",
          "Định nghĩa đã thay đổi; hãy tăng semanticVersion trước khi lưu.");
    // Data scans run last, only for a model that passed every metadata check.
    cardinality.verify(warehouse, tables, Json.objects(model.get("relationships")));
    return new Checked(dataset, model);
  }
}
