package dev.nla.query;

import dev.nla.ai.AiClient;
import dev.nla.common.ApiException;
import dev.nla.common.Errors;
import dev.nla.common.Json;
import dev.nla.common.Page;
import dev.nla.common.Requests;
import dev.nla.modelprovider.ModelProviderService;
import dev.nla.registry.CatalogService;
import dev.nla.store.Store;
import dev.nla.warehouse.*;
import dev.nla.warehouse.Adapters;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Question -> AI candidate SQL -> SQL guard (AST, table/column allowlist,
 * dialect) -> bounded
 * execution -> query history (T-1.3.2, guard and deadline T-2.2.1). Python
 * never executes SQL; Java
 * re-authorizes before using the reader credential.
 */
@Service
public class QueryService {
  private final Store store;
  private final CatalogService catalog;
  private final Adapters adapters;
  private final AiClient ai;
  private final SqlGuard guard;
  private final ModelProviderService providers;

  public QueryService(
      Store s,
      CatalogService c,
      Adapters a,
      AiClient ai,
      SqlGuard guard,
      ModelProviderService providers) {
    store = s;
    catalog = c;
    adapters = a;
    this.ai = ai;
    this.guard = guard;
    this.providers = providers;
  }

  public Map<String, Object> run(Map<String, Object> b) {
    Requests.fields(b, Set.of("warehouseId", "datasetId", "question", "mode", "providerId"));
    Requests.required(b, "warehouseId", "datasetId", "question");
    String question = Json.str(b, "question").strip();
    if (question.codePointCount(0, question.length()) > 1000)
      throw ApiException.bad("Câu hỏi tối đa 1000 ký tự.");
    String mode = Json.str(b, "mode");
    if (mode.isBlank())
      mode = "AUTO";
    if (!Set.of("AUTO", "API", "DETERMINISTIC").contains(mode))
      throw ApiException.bad("Mode không hợp lệ.");
    if (b.containsKey("providerId")
        && (!"API".equals(mode)
            || !(b.get("providerId") instanceof String providerId)
            || providerId.isBlank()
            || providerId.length() > 100))
      throw ApiException.bad("Chỉ chế độ API nhận providerId hợp lệ; AUTO dùng provider mặc định.");
    var w = store.warehouse(Json.str(b, "warehouseId"));
    var d = store.dataset(Json.str(b, "datasetId"));
    if (!w.get("warehouseId").equals(d.get("warehouseId")))
      throw ApiException.bad("Dataset không thuộc warehouse.");
    if (!Json.str(w, "status").equals("ACTIVE") || !Json.str(d, "status").equals("ACTIVE"))
      throw new ApiException(
          HttpStatus.CONFLICT, "DATASET_UNAVAILABLE", "Warehouse hoặc dataset không hoạt động.");
    return execute(w, d, question, mode, Json.str(b, "providerId"));
  }

  private Map<String, Object> execute(
      Map<String, Object> w,
      Map<String, Object> d,
      String question,
      String mode,
      String providerId) {
    String id = Json.id("qry");
    String now = Instant.now().toString();
    long start = System.nanoTime();
    var q = Json.obj(
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
        "provider",
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
        "sourceTables",
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
    QueryDeadline.start();
    try {
      var context = catalog.context(Json.str(d, "datasetId"));
      var request = Json.obj(
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
      if (!"DETERMINISTIC".equals(mode)) {
        // The chosen profile's config (with its decrypted key) goes only to the private
        // AI call;
        // history keeps a key-free snapshot of which provider answered.
        var provider = providerId.isBlank() ? providers.selectDefault() : providers.select(providerId);
        q.put("provider", provider.snapshot());
        provider.apply(request);
      }
      var candidate = ai.generate(request, mode);
      QueryDeadline.check();
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
      var validated = guard.validate(
          Json.str(candidate, "sql"),
          Json.str(w, "engine"),
          Json.objects(context.get("tables")));
      String sql = validated.sql();
      q.put("generatedSql", sql);
      q.put("sourceTables", validated.sourceTables());
      // The AI call can outlive a grant revocation or registry change: re-check
      // before running.
      var currentWarehouse = store.warehouse(Json.str(w, "warehouseId"));
      var currentDataset = store.dataset(Json.str(d, "datasetId"));
      if (!w.equals(currentWarehouse) || !d.equals(currentDataset))
        throw new ApiException(
            HttpStatus.CONFLICT,
            "SCHEMA_MISMATCH",
            "Warehouse hoặc cấu hình dataset đã thay đổi; chạy lại với metadata hiện tại.");
      QueryDeadline.check();
      var limits = Json.map(store.settings().get("limits"));
      var result = adapters
          .get(w)
          .executeReadOnlyQuery(
              w,
              adapters.password(store.credential(Json.str(w, "warehouseId"))),
              sql,
              ((Number) limits.get("maxRows")).intValue(),
              ((Number) limits.get("maxBytes")).intValue(),
              QueryDeadline.seconds(
                  Math.max(1, ((Number) limits.get("statementTimeoutMs")).intValue() / 1000)));
      QueryDeadline.check();
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
      org.slf4j.LoggerFactory.getLogger(QueryService.class)
          .warn(
              "Query failure engine={} SQLState={} vendorCode={} type={}",
              w.get("engine"),
              e.getSQLState(),
              e.getErrorCode(),
              e.getClass().getSimpleName());
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
    } finally {
      QueryDeadline.clear();
    }
    q.put("completedAt", Instant.now().toString());
    q.put("latencyMs", (System.nanoTime() - start) / 1000000);
    store.putQuery(q);
    store.audit("QUERY_" + q.get("status"), id);
    return publicQuery(q);
  }

  /**
   * Query terminal status for an error code, as listed in
   * docs/contracts/errors.md.
   */
  static String terminalStatus(String code) {
    if (code.equals("SQL_SECURITY_ERROR")
        || Set.of("UNSUPPORTED_QUESTION", "INSUFFICIENT_CONTEXT", "LOW_CONFIDENCE").contains(code))
      return "REJECTED";
    if (code.equals("QUERY_TIMEOUT"))
      return "TIMEOUT";
    if (Set.of(
        "SQL_VALIDATION_ERROR",
        "SQL_DIALECT_ERROR",
        "SEMANTIC_MODEL_INVALID",
        "SCHEMA_MISMATCH",
        "DATASET_UNAVAILABLE")
        .contains(code))
      return "VALIDATION_ERROR";
    return "QUERY_ERROR";
  }

  /**
   * A query is visible to its owner, or to ADMIN, while the warehouse is still
   * accessible.
   */
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

  public Map<String, Object> result(String id, Map<String, String> p) {
    var q = stored(id);
    if (Instant.parse(Json.str(q, "createdAt")).isBefore(Instant.now().minus(Duration.ofHours(24))))
      throw new ApiException(HttpStatus.GONE, "RESULT_EXPIRED", "Snapshot đã hết hạn lưu trữ.");
    var full = Json.map(q.get("result"));
    if (full.isEmpty())
      throw new ApiException(HttpStatus.CONFLICT, "QUERY_ERROR", "Query không có snapshot.");
    int page = Page.number(p, "page", 1, 1000000), size = Page.number(p, "pageSize", 20, 100);
    var rows = Json.objects(full.get("rows"));
    String search = p.getOrDefault("search", "").toLowerCase(Locale.ROOT);
    rows = rows.stream()
        .filter(row -> Json.write(row).toLowerCase(Locale.ROOT).contains(search))
        .toList();
    if (p.containsKey("sort") && !p.get("sort").isBlank()) {
      String sort = p.get("sort");
      var col = Json.objects(full.get("columns")).stream()
          .filter(c -> sort.equals(c.get("name")))
          .findFirst()
          .orElseThrow(() -> ApiException.bad("Cột sort không hợp lệ."));
      Comparator<Map<String, Object>> cmp = (a, b) -> {
        Object x = a.get(sort), y = b.get(sort);
        if (x == null)
          return y == null ? 0 : 1;
        if (y == null)
          return -1;
        if (Set.of("INTEGER", "BIGINT", "DECIMAL", "FLOAT").contains(col.get("dataType")))
          return new java.math.BigDecimal(x.toString())
              .compareTo(new java.math.BigDecimal(y.toString()));
        return x.toString().compareTo(y.toString());
      };
      String direction = p.getOrDefault("direction", "ASC");
      if (!Set.of("ASC", "DESC").contains(direction))
        throw ApiException.bad("Direction không hợp lệ.");
      if (direction.equals("DESC"))
        cmp = cmp.reversed();
      rows = rows.stream().sorted(cmp).toList();
    }
    var result = new LinkedHashMap<>(full);
    result.put("rows", rows.stream().skip((long) (page - 1) * size).limit(size).toList());
    result.put("page", page);
    result.put("pageSize", size);
    result.put("returnedRowCount", rows.size());
    return result;
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
              for (String key : List.of(
                  "generatedSql",
                  "candidateSql",
                  "result",
                  "chart",
                  "error",
                  "owner",
                  "assumptions",
                  "selectedMetrics",
                  "selectedDimensions",
                  "sourceTables",
                  "explanation",
                  "completedAt",
                  "sqlDialect"))
                out.remove(key);
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
