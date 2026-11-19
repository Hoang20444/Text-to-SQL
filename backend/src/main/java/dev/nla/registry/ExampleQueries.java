package dev.nla.registry;

import dev.nla.common.ApiException;
import dev.nla.common.Json;
import dev.nla.query.SqlGuard;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Checks the SQL examples of a semantic model before it is saved (T-3.1.2, AC2). Examples are sent
 * to the AI as few-shot answers, so a wrong one teaches wrong SQL: each must be written for the
 * warehouse dialect, pass the same AST guard as AI candidates on the live tables, and only name
 * metrics and dimensions defined in the model. The SQL is parsed, never executed.
 */
@Component
class ExampleQueries {
  private final SqlGuard guard;

  ExampleQueries(SqlGuard guard) {
    this.guard = guard;
  }

  void validate(String engine, Map<String, Object> model, List<Map<String, Object>> tables) {
    Set<String> metrics = names(model.get("metrics"));
    Set<String> dimensions = names(model.get("dimensions"));
    var examples = Json.objects(model.get("examples"));
    for (int i = 0; i < examples.size(); i++) {
      var example = examples.get(i);
      String path = "examples[" + i + "]";
      String question = Json.str(example, "question").strip();
      if (question.isEmpty() || question.length() > 1000)
        throw invalid("Ví dụ cần câu hỏi từ 1 đến 1000 ký tự.", path + ".question");
      if (!engine.equals(example.get("sqlDialect")))
        throw invalid(
            "Ví dụ phải viết cho phương ngữ " + engine + " của warehouse này.",
            path + ".sqlDialect");
      try {
        guard.validate(Json.str(example, "sql"), engine, tables);
      } catch (ApiException e) {
        throw invalid("SQL của ví dụ không hợp lệ: " + e.getMessage(), path + ".sql");
      }
      if (!metrics.containsAll(list(example.get("selectedMetrics"))))
        throw invalid("Ví dụ chọn metric không có trong mô hình.", path + ".selectedMetrics");
      if (!dimensions.containsAll(list(example.get("selectedDimensions"))))
        throw invalid("Ví dụ chọn dimension không có trong mô hình.", path + ".selectedDimensions");
    }
  }

  private static Set<String> names(Object definitions) {
    Set<String> result = new HashSet<>();
    Json.objects(definitions).forEach(d -> result.add(Json.str(d, "name")));
    return result;
  }

  private static List<?> list(Object value) {
    return value instanceof List<?> items ? items : List.of();
  }

  private static ApiException invalid(String message, String path) {
    return new ApiException(
        HttpStatus.CONFLICT, "SEMANTIC_MODEL_INVALID", message, Json.obj("path", path));
  }
}
