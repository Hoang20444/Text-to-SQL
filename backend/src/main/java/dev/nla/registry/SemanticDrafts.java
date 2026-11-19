package dev.nla.registry;

import dev.nla.common.ApiException;
import dev.nla.common.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Turns an AI semantic draft into a reviewable preview (T-3.2.2). Nothing is stored here: the admin
 * picks items and saves them through PUT semantic-model with a new version.
 *
 * <p>Every proposed definition is checked again with the same rules as PUT (live tables and
 * columns, metric formula through SqlGuard, compatible dimensions), so a made-up column is marked
 * INVALID instead of reaching the editor as a valid suggestion. Existing definitions are never
 * removed or changed: a proposal with an existing name is shown as CHANGED next to the current one.
 */
@Component
class SemanticDrafts {
  private static final Set<String> FIELDS =
      Set.of("model", "metrics", "dimensions", "businessRules", "warnings");

  private final CatalogService catalog;
  private final MetricExpressions metricExpressions;

  SemanticDrafts(CatalogService catalog, MetricExpressions metricExpressions) {
    this.catalog = catalog;
    this.metricExpressions = metricExpressions;
  }

  Map<String, Object> preview(
      String engine,
      Map<String, Object> current,
      List<Map<String, Object>> tables,
      Map<String, Object> proposal) {
    checkShape(proposal);
    var existingDimensions = Json.objects(current.get("dimensions"));
    var existingMetrics = Json.objects(current.get("metrics"));

    List<Map<String, Object>> dimensionItems = new ArrayList<>();
    var usableDimensions = byName(existingDimensions);
    for (var dimension : Json.objects(proposal.get("dimensions"))) {
      String problem =
          problem(
              () ->
                  catalog.validateSemantic(
                      Json.obj("dimensions", List.of(dimension), "metrics", List.of()), tables));
      var item = item(dimension, existingDimensions, problem);
      if (!"INVALID".equals(item.get("status"))) usableDimensions.put(key(dimension), dimension);
      dimensionItems.add(item);
    }

    List<Map<String, Object>> metricItems = new ArrayList<>();
    for (var metric : Json.objects(proposal.get("metrics"))) {
      var dimensions = new ArrayList<>(usableDimensions.values());
      // A dimension with the metric's own name would clash; uniqueness is checked with the rest.
      dimensions.removeIf(d -> key(d).equals(key(metric)));
      String problem =
          problem(
              () -> {
                catalog.validateSemantic(
                    Json.obj("dimensions", dimensions, "metrics", List.of(metric)), tables);
                metricExpressions.validate(engine, List.of(metric), tables);
              });
      metricItems.add(item(metric, existingMetrics, problem));
    }

    var currentRules = current.get("businessRules") instanceof List<?> list ? list : List.of();
    List<Map<String, Object>> ruleItems = new ArrayList<>();
    for (Object rule : (List<?>) proposal.get("businessRules"))
      ruleItems.add(
          Json.obj("status", currentRules.contains(rule) ? "UNCHANGED" : "NEW", "rule", rule));

    return Json.obj(
        "model",
        proposal.get("model"),
        "baseSemanticVersion",
        current.get("semanticVersion"),
        "suggestedSemanticVersion",
        nextVersion(Json.str(current, "semanticVersion")),
        "metrics",
        metricItems,
        "dimensions",
        dimensionItems,
        "businessRules",
        ruleItems,
        "warnings",
        proposal.get("warnings"));
  }

  /** The AI response must match the private contract before anything is compared. */
  private static void checkShape(Map<String, Object> proposal) {
    if (!proposal.keySet().equals(FIELDS)
        || !(proposal.get("model") instanceof String model)
        || model.isBlank()
        || model.length() > 500
        || !list(proposal.get("metrics"), 6, Map.class)
        || !list(proposal.get("dimensions"), 6, Map.class)
        || !list(proposal.get("businessRules"), 10, String.class)
        || !list(proposal.get("warnings"), 11, String.class))
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          "INVALID_MODEL_RESPONSE",
          "Model trả về bản nháp không đúng định dạng; hãy thử lại hoặc tạo định nghĩa thủ công.");
    for (String field : List.of("metrics", "dimensions"))
      for (var definition : Json.objects(proposal.get(field)))
        if (Json.str(definition, "name").isBlank() || Json.str(definition, "name").length() > 200)
          throw new ApiException(
              HttpStatus.BAD_GATEWAY,
              "INVALID_MODEL_RESPONSE",
              "Model trả về định nghĩa thiếu tên; hãy thử lại hoặc tạo định nghĩa thủ công.");
  }

  private static boolean list(Object value, int max, Class<?> type) {
    return value instanceof List<?> items
        && items.size() <= max
        && items.stream().allMatch(type::isInstance);
  }

  private static Map<String, Object> item(
      Map<String, Object> definition, List<Map<String, Object>> existing, String problem) {
    if (problem != null)
      return Json.obj("status", "INVALID", "definition", definition, "reason", problem);
    var match = existing.stream().filter(e -> key(e).equals(key(definition))).findFirst();
    if (match.isEmpty()) return Json.obj("status", "NEW", "definition", definition);
    if (match.get().equals(definition))
      return Json.obj("status", "UNCHANGED", "definition", definition);
    return Json.obj("status", "CHANGED", "definition", definition, "current", match.get());
  }

  /** The validation message for a rejected definition, or null when it passed. */
  private static String problem(Runnable check) {
    try {
      check.run();
      return null;
    } catch (ApiException e) {
      return e.getMessage();
    }
  }

  private static Map<String, Map<String, Object>> byName(List<Map<String, Object>> definitions) {
    var result = new LinkedHashMap<String, Map<String, Object>>();
    definitions.forEach(d -> result.put(key(d), d));
    return result;
  }

  private static String key(Map<String, Object> definition) {
    return Json.str(definition, "name").trim().toLowerCase(Locale.ROOT);
  }

  /** 0.0.0 becomes 1.0.0 (first definitions); otherwise the minor part goes up. */
  static String nextVersion(String version) {
    if (!SemanticVersions.validFormat(version) || version.equals("0.0.0")) return "1.0.0";
    String[] parts = version.split("\\.");
    return parts[0] + "." + (Integer.parseInt(parts[1]) + 1) + ".0";
  }
}
