package dev.nla.registry;

import dev.nla.common.ApiException;
import dev.nla.common.Json;
import dev.nla.query.SqlGuard;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Checks metric formulas against the live tables (T-3.1.2, AC2). Each expression is wrapped in a
 * SELECT over its own tableRefs and given to {@link SqlGuard}, the same AST check used for AI SQL:
 * unknown or ambiguous columns, subqueries and functions outside the allowlist are rejected. The
 * SELECT is only parsed, never executed.
 */
@Component
class MetricExpressions {
  private final SqlGuard guard;

  MetricExpressions(SqlGuard guard) {
    this.guard = guard;
  }

  void validate(
      String engine, List<Map<String, Object>> metrics, List<Map<String, Object>> tables) {
    for (int i = 0; i < metrics.size(); i++) {
      var metric = metrics.get(i);
      var refs = Json.objects(metric.get("tableRefs"));
      String path = "metrics[" + i + "].expression";
      String name = Json.str(metric, "name");
      if (refs.isEmpty())
        throw invalid(
            "Metric '" + name + "' cần ít nhất một bảng gốc.", "metrics[" + i + "].tableRefs");
      var refTables =
          tables.stream()
              .filter(
                  t ->
                      refs.stream()
                          .anyMatch(r -> CatalogService.key(r).equals(CatalogService.key(t))))
              .toList();
      String from =
          refs.stream()
              .map(
                  r ->
                      quote(engine, Json.str(r, "schema"))
                          + "."
                          + quote(engine, Json.str(r, "name")))
              .collect(Collectors.joining(", "));
      String sql = "SELECT " + Json.str(metric, "expression") + " AS metric_value FROM " + from;
      try {
        guard.validate(sql, engine, refTables);
      } catch (ApiException e) {
        throw invalid("Công thức của metric '" + name + "' không hợp lệ: " + e.getMessage(), path);
      }
    }
  }

  private static String quote(String engine, String identifier) {
    return switch (engine) {
      case "MYSQL" -> "`" + identifier.replace("`", "``") + "`";
      case "SQL_SERVER" -> "[" + identifier.replace("]", "]]") + "]";
      default -> "\"" + identifier.replace("\"", "\"\"") + "\"";
    };
  }

  private static ApiException invalid(String message, String path) {
    return new ApiException(
        HttpStatus.CONFLICT, "SEMANTIC_MODEL_INVALID", message, Json.obj("path", path));
  }
}
