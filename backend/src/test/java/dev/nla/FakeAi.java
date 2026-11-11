package dev.nla;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only stand-in for the Python AI service: returns a configured candidate and records what
 * Java sent. The real AI service is exercised in the manual end-to-end run.
 */
final class FakeAi {
  static final String TOKEN = "0123456789abcdef0123456789abcdef";
  final AtomicReference<Integer> status = new AtomicReference<>(200);
  final AtomicReference<String> body = new AtomicReference<>("{}");
  final AtomicReference<String> lastRequest = new AtomicReference<>();
  final AtomicReference<String> lastMode = new AtomicReference<>();
  final AtomicReference<String> lastAuth = new AtomicReference<>();
  private final HttpServer server;

  FakeAi() {
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
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void stop() {
    server.stop(0);
  }

  void answer(String sql) {
    status.set(200);
    body.set(candidate(sql));
  }

  static String candidate(String sql) {
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

  static final String SEMANTIC_MODEL =
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
