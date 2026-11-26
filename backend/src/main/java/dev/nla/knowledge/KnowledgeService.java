package dev.nla.knowledge;

import dev.nla.ai.AiClient;
import dev.nla.common.ApiException;
import dev.nla.common.Errors;
import dev.nla.common.Json;
import dev.nla.common.Requests;
import dev.nla.query.SqlGuard;
import dev.nla.registry.CatalogService;
import dev.nla.store.Store;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Dataset knowledge base seen from Java (T-4.1.2, US-4.1).
 *
 * <p>Python owns the vector index (LanceDB) and Java owns the approved question/SQL examples: the
 * index drops approvals whenever the semantic model changes, so every sync re-validates the
 * examples with the SQL guard against the current tables and sends them again. Syncs run in the
 * background, one at a time, and are recorded as {@code KNOWLEDGE} operation runs.
 */
@Service
public class KnowledgeService {
  static final String KIND = "KNOWLEDGE";
  static final int MAX_EXAMPLES = 1000;
  static final int INDEX_TIMEOUT_SECONDS = 300;
  static final int CALL_TIMEOUT_SECONDS = 60;
  static final Duration RUN_EXPIRY = Duration.ofMinutes(15);
  private static final Logger LOG = LoggerFactory.getLogger(KnowledgeService.class);

  private final Store store;
  private final CatalogService catalog;
  private final SqlGuard guard;
  private final AiClient ai;
  private final ExecutorService sync =
      Executors.newSingleThreadExecutor(
          task -> {
            Thread thread = new Thread(task, "knowledge-sync");
            thread.setDaemon(true);
            return thread;
          });

  public KnowledgeService(Store store, CatalogService catalog, SqlGuard guard, AiClient ai) {
    this.store = store;
    this.catalog = catalog;
    this.guard = guard;
    this.ai = ai;
  }

  @PreDestroy
  void stop() {
    sync.shutdownNow();
  }

  /** What the knowledge screen shows; never fails because the AI service is down (AC2, AC4). */
  public Map<String, Object> status(String datasetId) {
    var dataset = store.dataset(datasetId);
    var last = lastRun(datasetId);
    var approved = store.knowledgeExamples(datasetId);
    Map<String, Object> status;
    if (last != null && "RUNNING".equals(last.get("status"))) {
      status = fallback(dataset, last, "INDEXING", null);
    } else {
      try {
        var target = target(dataset);
        status =
            new LinkedHashMap<>(ai.knowledge("status", target.request(), CALL_TIMEOUT_SECONDS));
        status.remove("error");
        status.put("error", null);
      } catch (ApiException unavailable) {
        status = fallback(dataset, last, "ERROR", unavailable.getMessage());
      }
    }
    status.put("datasetId", datasetId);
    status.put("approvedExampleCount", approved.size());
    status.put("lastRun", last);
    return status;
  }

  /** Approved examples (Java) followed by the semantic model's examples for this engine. */
  public Map<String, Object> examples(String datasetId) {
    var dataset = store.dataset(datasetId);
    String engine = Json.str(store.warehouse(Json.str(dataset, "warehouseId")), "engine");
    List<Map<String, Object>> items = new ArrayList<>();
    store.knowledgeExamples(datasetId).forEach(e -> items.add(publicExample(e)));
    var configured = Json.objects(store.semantic(datasetId).get("examples"));
    for (int i = 0; i < configured.size(); i++) {
      var example = configured.get(i);
      if (!engine.equals(Json.str(example, "sqlDialect"))) continue;
      items.add(
          Json.obj(
              "entryId",
              "semantic-" + i,
              "question",
              example.get("question"),
              "sql",
              example.get("sql"),
              "sqlDialect",
              engine,
              "source",
              "semantic-config"));
    }
    return Json.obj("items", items, "total", items.size());
  }

  public record Added(Map<String, Object> body, boolean created) {}

  /** Stores a checked example and indexes it at once when the index is ready (AC3). */
  public Added addExample(String datasetId, Map<String, Object> body) {
    Requests.fields(body, Set.of("question", "sql"));
    Requests.required(body, "question", "sql");
    if (!(body.get("question") instanceof String rawQuestion)
        || !(body.get("sql") instanceof String rawSql))
      throw ApiException.bad("question và sql phải là chuỗi.");
    String question = rawQuestion.strip();
    if (question.isEmpty() || question.codePointCount(0, question.length()) > 1000)
      throw ApiException.bad("Câu hỏi mẫu phải từ 1 đến 1000 ký tự.");
    if (rawSql.isBlank() || rawSql.length() > 100000)
      throw ApiException.bad("SQL mẫu phải từ 1 đến 100000 ký tự.");
    var target = target(store.dataset(datasetId));
    // Same AST guard as AI candidates: read-only, this engine's dialect, dataset tables only.
    String sql = guard.validate(rawSql, target.engine(), target.tables()).sql();
    var existing = store.knowledgeExamples(datasetId);
    for (var example : existing)
      if (question.equals(example.get("question")) && sql.equals(example.get("sql")))
        return new Added(Json.obj("example", publicExample(example), "indexed", false), false);
    if (existing.size() >= MAX_EXAMPLES)
      throw ApiException.bad("Mỗi tập dữ liệu lưu tối đa " + MAX_EXAMPLES + " câu mẫu.");
    var example =
        Json.obj(
            "entryId",
            Json.id("kex"),
            "datasetId",
            datasetId,
            "warehouseId",
            target.warehouseId(),
            "question",
            question,
            "sql",
            sql,
            "sqlDialect",
            target.engine(),
            "source",
            "approved-query",
            "createdAt",
            Instant.now().toString(),
            "createdBy",
            store.actor());
    store.insertKnowledgeExample(example);
    boolean indexed = false;
    try {
      if ("READY"
          .equals(ai.knowledge("status", target.request(), CALL_TIMEOUT_SECONDS).get("status"))) {
        ai.knowledge(
            "remember",
            target.request("example", Json.obj("question", question, "sql", sql)),
            CALL_TIMEOUT_SECONDS);
        indexed = true;
      }
    } catch (ApiException unavailable) {
      // Kept in Java; the next sync sends it.
      indexed = false;
    }
    store.audit(
        "KNOWLEDGE_EXAMPLE_ADD",
        datasetId,
        Json.obj("entryId", example.get("entryId"), "indexed", indexed));
    return new Added(Json.obj("example", publicExample(example), "indexed", indexed), true);
  }

  /** Removes an approved example from the index first, so it can no longer be recalled. */
  public void deleteExample(String datasetId, String entryId) {
    var dataset = store.dataset(datasetId);
    if (entryId.startsWith("semantic-"))
      throw ApiException.bad("Câu mẫu của mô hình ngữ nghĩa chỉ sửa được qua mô hình ngữ nghĩa.");
    var example =
        store.knowledgeExamples(datasetId).stream()
            .filter(e -> entryId.equals(e.get("entryId")))
            .findFirst()
            .orElseThrow(ApiException::notFound);
    var target = target(dataset);
    if ("READY"
        .equals(ai.knowledge("status", target.request(), CALL_TIMEOUT_SECONDS).get("status")))
      forget(target, List.of(example));
    store.deleteKnowledgeExample(datasetId, entryId);
    store.audit("KNOWLEDGE_EXAMPLE_DELETE", datasetId, Json.obj("entryId", entryId));
  }

  /**
   * Starts a background sync, or returns the one already running for this dataset. The context is
   * captured now, under the caller's authorization.
   */
  public Map<String, Object> reindex(String datasetId, String trigger) {
    var target = target(store.dataset(datasetId));
    synchronized (this) {
      var last = lastRun(datasetId);
      if (last != null && "RUNNING".equals(last.get("status"))) return last;
      var run =
          Json.obj(
              "runId",
              Json.id("krun"),
              "datasetId",
              datasetId,
              "warehouseId",
              target.warehouseId(),
              "trigger",
              trigger,
              "status",
              "RUNNING",
              "startedAt",
              Instant.now().toString(),
              "completedAt",
              null,
              "rememberedCount",
              0,
              "skippedExamples",
              List.of(),
              "error",
              null);
      store.run(Json.str(run, "runId"), KIND, run);
      store.audit(
          "KNOWLEDGE_REINDEX", datasetId, Json.obj("runId", run.get("runId"), "trigger", trigger));
      sync.submit(() -> runSync(run, target));
      return run;
    }
  }

  public Map<String, Object> search(String datasetId, Map<String, Object> body) {
    Requests.fields(body, Set.of("question", "limit"));
    Requests.required(body, "question");
    if (!(body.get("question") instanceof String raw)
        || raw.isBlank()
        || raw.strip().codePointCount(0, raw.strip().length()) > 1000)
      throw ApiException.bad("Câu hỏi tìm thử phải từ 1 đến 1000 ký tự.");
    int limit = 8;
    if (body.containsKey("limit")) {
      if (!(body.get("limit") instanceof Integer value) || value < 1 || value > 20)
        throw ApiException.bad("limit phải là số nguyên từ 1 đến 20.");
      limit = value;
    }
    var target = target(store.dataset(datasetId));
    return ai.knowledge(
        "search", target.request("question", raw.strip(), "limit", limit), CALL_TIMEOUT_SECONDS);
  }

  /** index → re-validate and remember approved examples → drop approvals Java no longer has. */
  private void runSync(Map<String, Object> run, Target target) {
    var skipped = new ArrayList<Map<String, Object>>();
    int remembered = 0;
    try {
      var indexed = ai.knowledge("index", target.request(), INDEX_TIMEOUT_SECONDS);
      run.put("index", indexed);
      var approved = store.knowledgeExamples(target.datasetId());
      for (var example : approved) {
        String reason = null;
        try {
          guard.validate(Json.str(example, "sql"), target.engine(), target.tables());
        } catch (ApiException invalid) {
          reason = "Không còn khớp cấu trúc bảng hiện tại: " + invalid.getMessage();
        }
        if (reason == null) {
          try {
            ai.knowledge(
                "remember",
                target.request(
                    "example",
                    Json.obj("question", example.get("question"), "sql", example.get("sql"))),
                CALL_TIMEOUT_SECONDS);
            remembered++;
          } catch (ApiException unavailable) {
            reason = "Không gửi được câu mẫu sang kho tri thức.";
          }
        }
        if (reason != null)
          skipped.add(Json.obj("entryId", example.get("entryId"), "reason", reason));
      }
      // Python keeps approvals of an unchanged model across rebuilds; remove deleted ones.
      var stale = new ArrayList<Map<String, Object>>();
      for (var entry :
          Json.objects(
              ai.knowledge("examples", target.request(), CALL_TIMEOUT_SECONDS).get("items"))) {
        if (!"approved-query".equals(entry.get("source"))) continue;
        boolean kept =
            approved.stream()
                .anyMatch(
                    e ->
                        Objects.equals(e.get("question"), entry.get("question"))
                            && Objects.equals(e.get("sql"), entry.get("sql")));
        if (!kept) stale.add(entry);
      }
      for (var entry : stale)
        ai.knowledge(
            "forget", target.request("entryId", entry.get("entryId")), CALL_TIMEOUT_SECONDS);
      run.put("index", ai.knowledge("status", target.request(), CALL_TIMEOUT_SECONDS));
      run.put("status", skipped.isEmpty() ? "SUCCESS" : "PARTIAL");
    } catch (ApiException failure) {
      run.put("status", "FAILED");
      run.put("error", failure.getMessage());
    } catch (RuntimeException failure) {
      LOG.warn("Knowledge sync failed run={}", run.get("runId"), failure);
      run.put("status", "FAILED");
      run.put("error", "Đồng bộ kho tri thức gặp lỗi nội bộ.");
    }
    run.put("rememberedCount", remembered);
    run.put("skippedExamples", skipped);
    run.put("completedAt", Instant.now().toString());
    store.run(Json.str(run, "runId"), KIND, run);
  }

  /** Asks Python to drop the given approved examples; matches by question and SQL text. */
  private void forget(Target target, List<Map<String, Object>> examples) {
    var items =
        Json.objects(ai.knowledge("examples", target.request(), CALL_TIMEOUT_SECONDS).get("items"));
    Set<String> ids = new HashSet<>();
    for (var entry : items)
      for (var example : examples)
        if ("approved-query".equals(entry.get("source"))
            && Objects.equals(entry.get("question"), example.get("question"))
            && Objects.equals(entry.get("sql"), example.get("sql")))
          ids.add(Json.str(entry, "entryId"));
    for (String id : ids)
      ai.knowledge("forget", target.request("entryId", id), CALL_TIMEOUT_SECONDS);
  }

  /** Latest run; one left RUNNING too long (for example a restart) is closed as FAILED. */
  private Map<String, Object> lastRun(String datasetId) {
    var last = store.latestRun(KIND, datasetId);
    if (last != null
        && "RUNNING".equals(last.get("status"))
        && Instant.parse(Json.str(last, "startedAt")).isBefore(Instant.now().minus(RUN_EXPIRY))) {
      last.put("status", "FAILED");
      last.put("error", "Lần đồng bộ bị gián đoạn; hãy đồng bộ lại.");
      last.put("completedAt", Instant.now().toString());
      store.run(Json.str(last, "runId"), KIND, last);
    }
    return last;
  }

  /** Status built from Java's own records when Python is not asked or does not answer. */
  private Map<String, Object> fallback(
      Map<String, Object> dataset, Map<String, Object> last, String status, String error) {
    var known = last == null ? Map.<String, Object>of() : Json.map(last.get("index"));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("datasetId", dataset.get("datasetId"));
    out.put("status", status);
    out.put("schemaItems", known.getOrDefault("schemaItems", 0));
    out.put("exampleCount", known.getOrDefault("exampleCount", 0));
    out.put("embeddingModel", known.getOrDefault("embeddingModel", "unknown"));
    out.put("indexedAt", known.get("indexedAt"));
    out.put("semanticVersion", dataset.get("semanticVersion"));
    out.put("schemaVersion", dataset.get("schemaVersion"));
    out.put("error", error);
    return out;
  }

  private static Map<String, Object> publicExample(Map<String, Object> stored) {
    var out = new LinkedHashMap<>(stored);
    out.remove("datasetId");
    out.remove("warehouseId");
    return out;
  }

  private Target target(Map<String, Object> dataset) {
    String datasetId = Json.str(dataset, "datasetId");
    var warehouse = store.warehouse(Json.str(dataset, "warehouseId"));
    var context = catalog.context(datasetId);
    return new Target(
        datasetId,
        Json.str(warehouse, "warehouseId"),
        Json.str(warehouse, "engine"),
        context,
        Json.objects(context.get("tables")));
  }

  /** Everything a knowledge call needs, captured under the caller's authorization. */
  record Target(
      String datasetId,
      String warehouseId,
      String engine,
      Map<String, Object> context,
      List<Map<String, Object>> tables) {
    Map<String, Object> request(Object... extra) {
      var body =
          Json.obj(
              "correlationId",
              Errors.correlation(),
              "warehouseId",
              warehouseId,
              "datasetId",
              datasetId,
              "sqlDialect",
              engine,
              "semanticContext",
              context);
      for (int i = 0; i < extra.length; i += 2) body.put((String) extra[i], extra[i + 1]);
      return body;
    }
  }
}
