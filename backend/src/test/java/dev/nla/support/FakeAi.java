package dev.nla.support;

import com.sun.net.httpserver.HttpServer;
import dev.nla.common.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only stand-in for the Python AI service: returns a configured candidate and records what
 * Java sent. The real AI service is exercised in the manual end-to-end run.
 */
public final class FakeAi {
  public static final String TOKEN = "0123456789abcdef0123456789abcdef";
  public final AtomicReference<Integer> status = new AtomicReference<>(200);
  public final AtomicReference<String> body = new AtomicReference<>("{}");
  public final AtomicReference<String> lastRequest = new AtomicReference<>();
  public final AtomicReference<String> lastMode = new AtomicReference<>();
  public final AtomicReference<String> lastAuth = new AtomicReference<>();
  // semantic-assist (T-3.2.2) answers separately from text-to-sql.
  public final AtomicReference<Integer> assistStatus = new AtomicReference<>(200);
  public final AtomicReference<String> assistBody = new AtomicReference<>("{}");
  public final AtomicReference<String> lastAssistRequest = new AtomicReference<>();
  // Answer of /model-provider/status; null provider means the AI service has no model configured.
  public final AtomicReference<String> providerStatus =
      new AtomicReference<>("{\"provider\":null}");
  // Dataset knowledge store (T-4.1.2): a small in-memory stand-in for the LanceDB index.
  public final AtomicReference<String> knowledgeState = new AtomicReference<>("READY");
  public final AtomicBoolean knowledgeDown = new AtomicBoolean();
  public final AtomicBoolean indexFails = new AtomicBoolean();
  // When set, /knowledge/index waits for it, so a test can observe a RUNNING sync.
  public final AtomicReference<CountDownLatch> indexGate = new AtomicReference<>();
  public final List<String> knowledgeCalls = Collections.synchronizedList(new ArrayList<>());
  public final Map<String, String> knowledgeLastBody = new ConcurrentHashMap<>();
  public final List<Map<String, Object>> knowledgeEntries =
      Collections.synchronizedList(new ArrayList<>());
  private final HttpServer server;

  public FakeAi() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/internal/v1/knowledge/",
        exchange -> {
          String action = exchange.getRequestURI().getPath().replace("/internal/v1/knowledge/", "");
          String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          knowledgeCalls.add(action);
          knowledgeLastBody.put(action, raw);
          int code = 200;
          Object answer;
          try {
            answer = knowledge(action, Json.read(raw));
          } catch (IllegalStateException unavailable) {
            code = 503;
            answer = Json.obj("error", Json.obj("code", "KNOWLEDGE_UNAVAILABLE"));
          }
          byte[] bytes = Json.write(answer).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(code, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.createContext(
        "/internal/v1/text-to-sql",
        exchange -> {
          lastRequest.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          lastMode.set(exchange.getRequestHeaders().getFirst("X-Execution-Mode"));
          lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status.get(), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.createContext(
        "/internal/v1/semantic-assist",
        exchange -> {
          lastAssistRequest.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] bytes = assistBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(assistStatus.get(), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.createContext(
        "/internal/v1/model-provider/status",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] bytes = providerStatus.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public void stop() {
    server.stop(0);
  }

  public void resetKnowledge() {
    knowledgeState.set("READY");
    knowledgeDown.set(false);
    indexFails.set(false);
    indexGate.set(null);
    knowledgeCalls.clear();
    knowledgeLastBody.clear();
    knowledgeEntries.clear();
  }

  /** Same shapes as ai-service/knowledge.py; an IllegalStateException answers 503. */
  private Object knowledge(String action, Map<String, Object> request) {
    if (knowledgeDown.get()) throw new IllegalStateException();
    var context = Json.map(request.get("semanticContext"));
    switch (action) {
      case "status" -> {
        return knowledgeStatus(request, context);
      }
      case "index" -> {
        var gate = indexGate.get();
        if (gate != null) {
          try {
            gate.await(20, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        if (indexFails.get()) throw new IllegalStateException();
        knowledgeState.set("READY");
        return knowledgeStatus(request, context);
      }
      default -> {
        if (!"READY".equals(knowledgeState.get())) throw new IllegalStateException();
      }
    }
    switch (action) {
      case "remember" -> {
        var example = Json.map(request.get("example"));
        String entryId =
            Integer.toHexString(Objects.hash(example.get("question"), example.get("sql")));
        synchronized (knowledgeEntries) {
          if (knowledgeEntries.stream().noneMatch(e -> entryId.equals(e.get("entryId"))))
            knowledgeEntries.add(
                Json.obj(
                    "entryId",
                    entryId,
                    "question",
                    example.get("question"),
                    "sql",
                    example.get("sql"),
                    "sqlDialect",
                    request.get("sqlDialect"),
                    "source",
                    "approved-query"));
        }
        return knowledgeExamples();
      }
      case "forget" -> {
        knowledgeEntries.removeIf(e -> Objects.equals(e.get("entryId"), request.get("entryId")));
        return knowledgeExamples();
      }
      case "examples" -> {
        return knowledgeExamples();
      }
      case "search" -> {
        return Json.obj(
            "strategy",
            "vector",
            "question",
            request.get("question"),
            "embeddingModel",
            "embeddinggemma:300m",
            "hits",
            List.of(
                Json.obj(
                    "itemType",
                    "model",
                    "name",
                    "sales.orders",
                    "modelName",
                    "sales.orders",
                    "text",
                    "Bảng đơn hàng",
                    "distance",
                    0.12)),
            "examples",
            knowledgeExamples().get("items"));
      }
      default -> throw new IllegalStateException();
    }
  }

  private Map<String, Object> knowledgeExamples() {
    synchronized (knowledgeEntries) {
      return Json.obj("items", List.copyOf(knowledgeEntries), "total", knowledgeEntries.size());
    }
  }

  private Map<String, Object> knowledgeStatus(
      Map<String, Object> request, Map<String, Object> context) {
    boolean empty = "EMPTY".equals(knowledgeState.get());
    return Json.obj(
        "datasetId",
        request.get("datasetId"),
        "status",
        knowledgeState.get(),
        "schemaItems",
        empty ? 0 : 7,
        "exampleCount",
        empty ? 0 : knowledgeEntries.size(),
        "embeddingModel",
        "embeddinggemma:300m",
        "indexedAt",
        empty ? null : "2026-11-26T00:00:00Z",
        "semanticVersion",
        context.get("semanticVersion"),
        "schemaVersion",
        context.get("schemaVersion"),
        "error",
        null);
  }

  public void answer(String sql) {
    status.set(200);
    body.set(candidate(sql));
  }

  /** Makes the AI service report a configured local Ollama model, like a real deployment. */
  public void useEnvironmentProvider() {
    providerStatus.set(
        Json.write(
            Json.obj(
                "provider",
                Json.obj(
                    "providerId",
                    "environment",
                    "revision",
                    "env-1",
                    "name",
                    "Ollama local",
                    "kind",
                    "OLLAMA",
                    "baseUrl",
                    "http://127.0.0.1:11434/v1",
                    "model",
                    "qwen2.5-coder:7b",
                    "hasApiKey",
                    false,
                    "enabled",
                    true,
                    "configured",
                    true,
                    "source",
                    "ENVIRONMENT",
                    "readOnly",
                    true,
                    "updatedAt",
                    "2026-11-19T00:00:00Z"))));
  }

  public static String candidate(String sql) {
    return Json.write(
        Json.obj(
            "sql",
            sql,
            "explanation",
            "Tổng tiền theo quốc gia.",
            "assumptions",
            List.of("Số tiền theo USD."),
            "selectedMetrics",
            List.of("total_amount"),
            "selectedDimensions",
            List.of("country"),
            "chartRecommendation",
            Json.obj(
                "type",
                "bar",
                "title",
                "Tổng tiền theo quốc gia",
                "x",
                "country",
                "series",
                List.of(Json.obj("column", "total_amount", "label", "Tổng tiền"))),
            "confidence",
            1.0,
            "model",
            "deterministic-semantic-examples"));
  }

  public static final String SEMANTIC_MODEL =
      """
      {"semanticVersion":"1.0.0","schemaVersion":"test-1","timezone":"Asia/Ho_Chi_Minh",
       "tables":[],"relationships":[],"businessRules":["Không cộng trùng đơn hàng."],
       "metrics":[{"name":"total_amount","expression":"SUM(amount)",
         "tableRefs":[{"schema":"sales","name":"orders"}],"compatibleDimensions":["country"]}],
       "dimensions":[{"name":"country","table":{"schema":"sales","name":"orders"},
         "column":"country","dataType":"STRING"}],
       "examples":[]}
      """;
}
