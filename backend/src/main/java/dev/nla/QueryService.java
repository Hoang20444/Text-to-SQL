package dev.nla;

import dev.nla.warehouse.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Question -> AI candidate SQL -> Java read-only check -> bounded execution -> query history
 * (T-1.3.2). Python never executes SQL; Java re-authorizes before using the reader credential.
 */
@Service
public class QueryService {
  private final Store store;
  private final CatalogService catalog;
  private final Adapters adapters;
  private final AiClient ai;

  public QueryService(Store s, CatalogService c, Adapters a, AiClient ai) {
    store = s;
    catalog = c;
    adapters = a;
    this.ai = ai;
  }

  public Map<String, Object> run(Map<String, Object> b) {
    RegistryController.fields(b, Set.of("warehouseId", "datasetId", "question", "mode"));
    RegistryController.required(b, "warehouseId", "datasetId", "question");
    String question = Json.str(b, "question").strip();
    if (question.codePointCount(0, question.length()) > 1000)
      throw ApiException.bad("Câu hỏi tối đa 1000 ký tự.");
    String mode = Json.str(b, "mode");
    if (mode.isBlank()) mode = "AUTO";
    if (!Set.of("AUTO", "API", "DETERMINISTIC").contains(mode))
      throw ApiException.bad("Mode không hợp lệ.");
    var w = store.warehouse(Json.str(b, "warehouseId"));
    var d = store.dataset(Json.str(b, "datasetId"));
    if (!w.get("warehouseId").equals(d.get("warehouseId")))
      throw ApiException.bad("Dataset không thuộc warehouse.");
    if (!Json.str(w, "status").equals("ACTIVE") || !Json.str(d, "status").equals("ACTIVE"))
      throw new ApiException(
          HttpStatus.CONFLICT, "DATASET_UNAVAILABLE", "Warehouse hoặc dataset không hoạt động.");
    return execute(w, d, question, mode);
  }

  private Map<String, Object> execute(
      Map<String, Object> w, Map<String, Object> d, String question, String mode) {
    String id = Json.id("qry");
    String now = Instant.now().toString();
    long start = System.nanoTime();
    var q =
        Json.obj(
            "queryId",
            id,
            "correlationId",
            Errors.correlation(),
            "warehouseId",
            w.get("warehouseId"),
            "datasetId",
            d.get("datasetId"),
            "engine",
            w.get("engine"),
            "question",
            question,
            "status",
            "RUNNING",
            "model",
            null,
            "executionMode",
            mode.equals("DETERMINISTIC") ? "DETERMINISTIC" : "API",
            "semanticVersion",
            d.get("semanticVersion"),
            "schemaVersion",
            d.get("schemaVersion"),
            "latencyMs",
            null,
            "createdAt",
            now,
            "completedAt",
            null,
            "candidateSql",
            null,
            "generatedSql",
            null,
            "sqlDialect",
            w.get("engine"),
            "explanation",
            null,
            "assumptions",
            List.of(),
            "selectedMetrics",
            List.of(),
            "selectedDimensions",
            List.of(),
            "result",
            null,
            "chart",
            null,
            "error",
            null,
            "owner",
            store.actor());
    store.putQuery(q);
    try {
      var context = catalog.context(Json.str(d, "datasetId"));
      var request =
          Json.obj(
              "correlationId",
              Json.str(q, "correlationId"),
              "warehouseId",
              w.get("warehouseId"),
              "datasetId",
              d.get("datasetId"),
              "sqlDialect",
              w.get("engine"),
              "question",
              question,
              "semanticContext",
              context);
      var candidate = ai.generate(request, mode);
      q.put("model", candidate.get("model"));
      q.put("candidateSql", candidate.get("sql"));
      for (String field : List.of("selectedMetrics", "selectedDimensions")) {
        String category = field.equals("selectedMetrics") ? "metrics" : "dimensions";
        Set<String> names = new HashSet<>();
        Json.objects(context.get(category)).forEach(x -> names.add(Json.str(x, "name")));
        if (!names.containsAll((List<?>) candidate.get(field)))
          throw new ApiException(
              HttpStatus.UNPROCESSABLE_ENTITY,
              "SQL_VALIDATION_ERROR",
              "AI chọn metric hoặc dimension ngoài semantic context.");
      }
      String sql = ReadOnlySqlCheck.requireSingleSelect(Json.str(candidate, "sql"));
      q.put("generatedSql", sql);
      // The AI call can outlive a grant revocation or registry change: re-check before running.
      var currentWarehouse = store.warehouse(Json.str(w, "warehouseId"));
      var currentDataset = store.dataset(Json.str(d, "datasetId"));
      if (!w.equals(currentWarehouse) || !d.equals(currentDataset))
        throw new ApiException(
            HttpStatus.CONFLICT,
            "SCHEMA_MISMATCH",
            "Warehouse hoặc cấu hình dataset đã thay đổi; chạy lại với metadata hiện tại.");
      var limits = Json.map(store.settings().get("limits"));
      var result =
          adapters
              .get(w)
              .executeReadOnlyQuery(
                  w,
                  adapters.password(store.credential(Json.str(w, "warehouseId"))),
                  sql,
                  ((Number) limits.get("maxRows")).intValue(),
                  ((Number) limits.get("maxBytes")).intValue(),
                  Math.max(1, ((Number) limits.get("statementTimeoutMs")).intValue() / 1000));
      q.put("result", result);
      q.put("chart", candidate.get("chartRecommendation"));
      q.put("explanation", candidate.get("explanation"));
      q.put("assumptions", candidate.get("assumptions"));
      q.put("selectedMetrics", candidate.get("selectedMetrics"));
      q.put("selectedDimensions", candidate.get("selectedDimensions"));
      q.put("status", "SUCCESS");
    } catch (ApiException e) {
      q.put("status", terminalStatus(e.code));
      q.put("error", Errors.body(e.code, e.getMessage(), Json.obj("queryId", id)));
    } catch (SQLException e) {
      boolean timeout = e instanceof SQLTimeoutException || "57014".equals(e.getSQLState());
      q.put("status", timeout ? "TIMEOUT" : "QUERY_ERROR");
      q.put(
          "error",
          Errors.body(
              timeout ? "QUERY_TIMEOUT" : "QUERY_ERROR",
              timeout
                  ? "Truy vấn hết thời gian xử lý."
                  : "Không thực thi được truy vấn trên warehouse.",
              Json.obj("queryId", id)));
    } catch (Exception e) {
      q.put("status", "QUERY_ERROR");
      q.put(
          "error",
          Errors.body("QUERY_ERROR", "Không xử lý được truy vấn.", Json.obj("queryId", id)));
    }
    q.put("completedAt", Instant.now().toString());
    q.put("latencyMs", (System.nanoTime() - start) / 1000000);
    store.putQuery(q);
    store.audit("QUERY_" + q.get("status"), id);
    return publicQuery(q);
  }

  /** Query terminal status for an error code, as listed in docs/contracts/errors.md. */
  static String terminalStatus(String code) {
    if (code.equals("SQL_SECURITY_ERROR")
        || Set.of("UNSUPPORTED_QUESTION", "INSUFFICIENT_CONTEXT", "LOW_CONFIDENCE").contains(code))
      return "REJECTED";
    if (code.equals("QUERY_TIMEOUT")) return "TIMEOUT";
    if (Set.of(
            "SQL_VALIDATION_ERROR",
            "SQL_DIALECT_ERROR",
            "SEMANTIC_MODEL_INVALID",
            "SCHEMA_MISMATCH",
            "DATASET_UNAVAILABLE")
        .contains(code)) return "VALIDATION_ERROR";
    return "QUERY_ERROR";
  }

  /** A query is visible to its owner, or to ADMIN, while the warehouse is still accessible. */
  public Map<String, Object> stored(String id) {
    var q = store.get("query_history", id);
    store.authorize(Json.str(q, "warehouseId"));
    if (!store.admin() && !Json.str(q, "owner").equals(store.actor()))
      throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Không có quyền xem truy vấn.");
    return q;
  }

  public Map<String, Object> get(String id) {
    return publicQuery(stored(id));
  }

  public Map<String, Object> publicQuery(Map<String, Object> stored) {
    var q = new LinkedHashMap<>(stored);
    q.remove("owner");
    if (q.get("result") != null) {
      var full = Json.map(q.get("result"));
      var page = new LinkedHashMap<>(full);
      page.put("rows", Json.objects(full.get("rows")).stream().limit(20).toList());
      page.put("pageSize", 20);
      q.put("result", page);
    }
    return q;
  }

  public List<Map<String, Object>> history() {
    return store.all("query_history").stream()
        .filter(
            q -> {
              try {
                stored(Json.str(q, "queryId"));
                return true;
              } catch (ApiException e) {
                return false;
              }
            })
        .map(
            q -> {
              var out = new LinkedHashMap<>(q);
              for (String key :
                  List.of(
                      "generatedSql",
                      "candidateSql",
                      "result",
                      "chart",
                      "error",
                      "owner",
                      "assumptions",
                      "selectedMetrics",
                      "selectedDimensions",
                      "explanation",
                      "completedAt",
                      "sqlDialect")) out.remove(key);
              out.put(
                  "rowCount",
                  q.get("result") == null
                      ? null
                      : Json.map(q.get("result")).get("returnedRowCount"));
              return (Map<String, Object>) out;
            })
        .toList();
  }
}
