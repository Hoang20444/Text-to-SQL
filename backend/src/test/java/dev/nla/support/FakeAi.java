package dev.nla.support;

import com.sun.net.httpserver.HttpServer;
import dev.nla.common.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
  private final HttpServer server;

  public FakeAi() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
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
        "/internal/v1/model-provider/status",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] bytes = "{\"provider\":null}".getBytes(StandardCharsets.UTF_8);
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

  public void answer(String sql) {
    status.set(200);
    body.set(candidate(sql));
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
            Json.obj("type", "bar", "x", "country"),
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
