package com.fsa.textsql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsa.textsql.query.WarehouseConnector;
import com.fsa.textsql.security.TestJwt;
import com.fsa.textsql.warehouse.WarehouseRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-1.3.2 end to end: POST /api/v1/query -> authorization -> fake Python AI (records what Java
 * sends) -> real PostgreSQL warehouse through a read-only reader account -> normalized result and
 * history. The 3-engine run against the real AI service is verified separately (see README).
 */
@SpringBootTest(
    properties = {
      "spring.config.import=",
      "nla.local-bootstrap.enabled=false",
      "nla.query.max-rows=3",
      "nla.query.allowed-hosts=",
      "nla.ai.token=0123456789abcdef0123456789abcdef",
      "TEST_WH_READER_PASSWORD=reader-secret-123"
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(TestJwt.DecoderConfig.class)
class QueryIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

  /** Fake AI service: returns the configured response and records the last request. */
  static final HttpServer AI;

  static final AtomicReference<Integer> AI_STATUS = new AtomicReference<>(200);
  static final AtomicReference<String> AI_BODY = new AtomicReference<>("{}");
  static final AtomicReference<String> AI_LAST_REQUEST = new AtomicReference<>();
  static final AtomicReference<String> AI_LAST_MODE = new AtomicReference<>();
  static final AtomicReference<String> AI_LAST_AUTH = new AtomicReference<>();

  static {
    try {
      AI = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    AI.createContext(
        "/internal/v1/text-to-sql",
        exchange -> {
          AI_LAST_REQUEST.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          AI_LAST_MODE.set(exchange.getRequestHeaders().getFirst("X-Execution-Mode"));
          AI_LAST_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] body = AI_BODY.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(AI_STATUS.get(), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    AI.start();
  }

  @DynamicPropertySource
  static void aiUrl(DynamicPropertyRegistry registry) {
    registry.add("nla.ai.url", () -> "http://127.0.0.1:" + AI.getAddress().getPort());
  }

  @AfterAll
  static void stopAi() {
    AI.stop(0);
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;
  @Autowired WarehouseConnector connector;
  @Autowired WarehouseRepository warehouses;

  private static final String SEMANTIC_MODEL =
      """
      {"metrics":[{"name":"total_amount","description":"Tổng tiền","expression":"SUM(sales.orders.amount)",
        "unit":"USD","tableRefs":[{"schema":"sales","name":"orders"}],"compatibleDimensions":["country"],
        "synonyms":["doanh thu"]}],
       "dimensions":[{"name":"country","description":"Quốc gia","table":{"schema":"sales","name":"orders"},
        "column":"country","dataType":"STRING","synonyms":["quốc gia"]}],
       "relationships":[],
       "examples":[
        {"question":"Doanh thu theo quốc gia?","sqlDialect":"POSTGRESQL",
         "sql":"SELECT country, SUM(amount) AS total_amount FROM sales.orders GROUP BY country",
         "selectedMetrics":["total_amount"],"selectedDimensions":["country"],"category":"Sales","language":"vi"},
        {"question":"How many orders?","sqlDialect":"POSTGRESQL",
         "sql":"SELECT COUNT(*) AS order_count FROM sales.orders",
         "selectedMetrics":[],"selectedDimensions":[],"category":"Sales","language":"en"},
        {"question":"Doanh thu theo quốc gia?","sqlDialect":"MYSQL",
         "sql":"SELECT `country`, SUM(`amount`) AS total_amount FROM `sales`.`orders` GROUP BY `country`",
         "selectedMetrics":["total_amount"],"selectedDimensions":["country"],"category":"Sales","language":"vi"}],
       "businessRules":["Không cộng trùng đơn hàng."],
       "semanticVersion":"1.0.0","schemaVersion":"test-1","timezone":"Asia/Ho_Chi_Minh"}
      """;

  @BeforeEach
  void seed() {
    // A real warehouse schema with a SELECT-only reader account, inside the test container.
    jdbc.sql(
            """
            CREATE SCHEMA IF NOT EXISTS sales;
            CREATE TABLE IF NOT EXISTS sales.orders (
              order_id bigint PRIMARY KEY, country varchar(50) NOT NULL,
              amount numeric(10,2) NOT NULL, placed_at timestamp NOT NULL,
              is_paid boolean NOT NULL, score double precision);
            INSERT INTO sales.orders VALUES
              (1,'VN',100.00,'2026-01-01 07:00:00',true,1.5),
              (2,'VN',200.00,'2026-01-02 07:00:00',false,NULL),
              (3,'US',50.25,'2026-01-03 07:00:00',true,2.0),
              (4,'US',49.75,'2026-01-04 07:00:00',true,2.5),
              (5,'JP',10.00,'2026-01-05 07:00:00',false,3.0)
            ON CONFLICT DO NOTHING;
            DO $$ BEGIN
              IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wh_reader') THEN
                CREATE ROLE wh_reader LOGIN PASSWORD 'reader-secret-123';
              END IF;
            END $$;
            GRANT USAGE ON SCHEMA sales TO wh_reader;
            GRANT SELECT ON sales.orders TO wh_reader;
            """)
        .update();
    for (String id : List.of("wh-test-pg", "wh-test-other")) {
      jdbc.sql(
              """
              INSERT INTO warehouses (id, name, engine, status, host, port, database_name,
                default_schema, username, credential_reference)
              VALUES (:id, :name, 'POSTGRESQL', 'ACTIVE', :host, :port, :db, 'sales', 'wh_reader',
                'env:TEST_WH_READER_PASSWORD')
              ON CONFLICT DO NOTHING
              """)
          .param("id", id)
          .param("name", "Test " + id)
          .param("host", db.getHost())
          .param("port", db.getMappedPort(5432))
          .param("db", db.getDatabaseName())
          .update();
      jdbc.sql(
              """
              INSERT INTO datasets (id, warehouse_id, name, status, allowed_schemas, allowed_tables,
                semantic_version, schema_version, semantic_model)
              VALUES (:id, :wh, 'Sales', 'ACTIVE', '["sales"]',
                '[{"schema":"sales","name":"orders"}]', '1.0.0', 'test-1', CAST(:model AS jsonb))
              ON CONFLICT DO NOTHING
              """)
          .param("id", id.replace("wh-", "ds-"))
          .param("wh", id)
          .param("model", SEMANTIC_MODEL)
          .update();
    }
    jdbc.sql(
            "INSERT INTO warehouse_access (subject, warehouse_id) VALUES (:s, 'wh-test-pg')"
                + " ON CONFLICT DO NOTHING")
        .param("s", TestJwt.ANALYST_SUB)
        .update();
    AI_STATUS.set(200);
    AI_BODY.set(
        candidate(
            "SELECT country, SUM(amount) AS total_amount FROM sales.orders GROUP BY country ORDER BY country"));
  }

  private String candidate(String sql) {
    return candidate(sql, "country", "total_amount");
  }

  private String candidate(String sql, String x, String seriesColumn) {
    try {
      return json.writeValueAsString(
          Map.of(
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
              Map.of(
                  "type",
                  "bar",
                  "title",
                  "Doanh thu theo quốc gia",
                  "x",
                  x,
                  "series",
                  List.of(Map.of("column", seriesColumn, "label", "Doanh thu"))),
              "confidence",
              1.0,
              "model",
              "deterministic-semantic-examples"));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private ResultActions ask(String token, Object body) throws Exception {
    return mvc.perform(
        post("/api/v1/query")
            .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  private static Map<String, Object> question(String warehouseId, String datasetId) {
    return Map.of(
        "warehouseId",
        warehouseId,
        "datasetId",
        datasetId,
        "question",
        "Doanh thu theo quốc gia?",
        "mode",
        "DETERMINISTIC");
  }

  private long historyCount() {
    return jdbc.sql("SELECT count(*) FROM query_history").query(Long.class).single();
  }

  private String historyStatus(String queryId) {
    return jdbc.sql("SELECT status FROM query_history WHERE id = :id")
        .param("id", queryId)
        .query(String.class)
        .single();
  }

  // ---------------- Success path ----------------

  @Test
  void runsReadOnlyQueryAndReturnsNormalizedResult() throws Exception {
    String body =
        ask(TestJwt.analyst(), question("wh-test-pg", "ds-test-pg"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.engine").value("POSTGRESQL"))
            .andExpect(jsonPath("$.sqlDialect").value("POSTGRESQL"))
            .andExpect(jsonPath("$.executionMode").value("DETERMINISTIC"))
            .andExpect(jsonPath("$.model").value("deterministic-semantic-examples"))
            .andExpect(
                jsonPath("$.generatedSql")
                    .value(org.hamcrest.Matchers.startsWith("SELECT country")))
            .andExpect(jsonPath("$.latencyMs").isNumber())
            .andExpect(jsonPath("$.semanticVersion").value("1.0.0"))
            .andExpect(jsonPath("$.schemaVersion").value("test-1"))
            .andExpect(jsonPath("$.result.columns[0].name").value("country"))
            .andExpect(jsonPath("$.result.columns[0].dataType").value("STRING"))
            .andExpect(jsonPath("$.result.columns[0].semanticRole").value("DIMENSION"))
            .andExpect(jsonPath("$.result.columns[1].dataType").value("DECIMAL"))
            .andExpect(jsonPath("$.result.columns[1].semanticRole").value("METRIC"))
            .andExpect(jsonPath("$.result.rows[*].country", contains("JP", "US", "VN")))
            // DECIMAL travels as a string to keep precision.
            .andExpect(jsonPath("$.result.rows[2].total_amount").value("300.00"))
            .andExpect(jsonPath("$.result.returnedRowCount").value(3))
            .andExpect(jsonPath("$.result.truncated").value(false))
            .andExpect(jsonPath("$.chart.type").value("bar"))
            .andExpect(jsonPath("$.sourceTables[0].name").value("orders"))
            .andExpect(jsonPath("$.error").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String queryId = json.readTree(body).get("queryId").asText();
    assertThat(historyStatus(queryId)).isEqualTo("SUCCESS");
    Map<String, Object> row =
        jdbc.sql("SELECT row_count, owner_subject, generated_sql FROM query_history WHERE id = :id")
            .param("id", queryId)
            .query()
            .singleRow();
    assertThat(row.get("row_count")).isEqualTo(3);
    assertThat(row.get("owner_subject")).isEqualTo(TestJwt.ANALYST_SUB);
    assertThat((String) row.get("generated_sql")).startsWith("SELECT country");
  }

  @Test
  void sendsOnlyAllowedContextToAiAndNeverCredentials() throws Exception {
    ask(TestJwt.analyst(), question("wh-test-pg", "ds-test-pg")).andExpect(status().isCreated());

    String sent = AI_LAST_REQUEST.get();
    assertThat(sent).doesNotContain("reader-secret-123", "TEST_WH_READER_PASSWORD", "wh_reader");
    assertThat(sent).doesNotContain(db.getHost() + ":" + db.getMappedPort(5432));
    assertThat(AI_LAST_MODE.get()).isEqualTo("DETERMINISTIC");
    assertThat(AI_LAST_AUTH.get()).isEqualTo("Bearer 0123456789abcdef0123456789abcdef");

    JsonNode request = json.readTree(sent);
    assertThat(request.get("sqlDialect").asText()).isEqualTo("POSTGRESQL");
    JsonNode context = request.get("semanticContext");
    assertThat(context.get("tables")).hasSize(1);
    assertThat(context.get("tables").get(0).get("name").asText()).isEqualTo("orders");
    assertThat(context.get("tables").get(0).get("primaryKey").get(0).asText())
        .isEqualTo("order_id");
    assertThat(context.get("tables").get(0).get("columns")).hasSize(6);
    // Only examples of the warehouse dialect are sent.
    assertThat(context.get("examples")).hasSize(2);
    assertThat(context.get("timezone").asText()).isEqualTo("Asia/Ho_Chi_Minh");
  }

  @Test
  void convertsTypesAndTruncatesAtMaxRows() throws Exception {
    AI_BODY.set(
        candidate(
            "SELECT order_id, placed_at, is_paid, score, COUNT(*) OVER () AS total"
                + " FROM sales.orders ORDER BY order_id"));
    ask(TestJwt.analyst(), question("wh-test-pg", "ds-test-pg"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.result.returnedRowCount").value(3))
        .andExpect(jsonPath("$.result.truncated").value(true))
        .andExpect(jsonPath("$.result.rows[0].order_id").value("1"))
        // 07:00 local time in Asia/Ho_Chi_Minh (+07) is 00:00 UTC.
        .andExpect(jsonPath("$.result.rows[0].placed_at").value("2026-01-01T00:00:00Z"))
        .andExpect(jsonPath("$.result.columns[1].dataType").value("TIMESTAMP"))
        .andExpect(jsonPath("$.result.rows[0].is_paid").value(true))
        .andExpect(jsonPath("$.result.rows[0].score").value(1.5))
        .andExpect(jsonPath("$.result.rows[1].score").doesNotExist())
        .andExpect(jsonPath("$.result.rows[0].total").value("5"))
        // Chart references columns missing from the result -> falls back to a table.
        .andExpect(jsonPath("$.chart.type").value("table"))
        .andExpect(jsonPath("$.chart.reason").exists());
  }

  // ---------------- Rejections and failures ----------------

  @Test
  void aiRejectionIsReturnedAndRecorded() throws Exception {
    AI_STATUS.set(422);
    AI_BODY.set("{\"error\":{\"code\":\"UNSUPPORTED_QUESTION\",\"message\":\"x\"}}");
    String body =
        ask(TestJwt.analyst(), question("wh-test-pg", "ds-test-pg"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_QUESTION"))
            .andExpect(jsonPath("$.error.details.status").value("REJECTED"))
            .andExpect(jsonPath("$.error.details.queryId").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String queryId = json.readTree(body).at("/error/details/queryId").asText();
    assertThat(historyStatus(queryId)).isEqualTo("REJECTED");
  }

  @Test
  void writeSqlFromAiIsBlockedBeforeExecution() throws Exception {
    AI_BODY.set(candidate("DELETE FROM sales.orders"));
    ask(TestJwt.analyst(), question("wh-test-pg", "ds-test-pg"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("SQL_SECURITY_ERROR"))
        .andExpect(jsonPath("$.error.details.status").value("REJECTED"));

    AI_BODY.set(candidate("SELECT 1; DROP TABLE sales.orders"));
    ask(TestJwt.analyst(), question("wh-test-pg", "ds-test-pg"))
        .andExpect(jsonPath("$.error.code").value("SQL_SECURITY_ERROR"));

    assertThat(jdbc.sql("SELECT count(*) FROM sales.orders").query(Long.class).single())
        .isEqualTo(5);
  }

  @Test
  void readerConnectionCannotWriteEvenIfTheCheckWereBypassed() throws Exception {
    var warehouse = warehouses.findConnection("wh-test-pg").orElseThrow();
    try (Connection c = connector.openReadOnly(warehouse);
        Statement s = c.createStatement()) {
      assertThatThrownBy(() -> s.executeUpdate("DELETE FROM sales.orders WHERE order_id = 1"))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void incompleteAiCandidateAndAiOutageGiveLlmError() throws Exception {
    AI_BODY.set("{\"sql\":\"SELECT 1\"}");
    ask(TestJwt.analyst(), question("wh-test-pg", "ds-test-pg"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.code").value("LLM_ERROR"))
        .andExpect(jsonPath("$.error.details.status").value("QUERY_ERROR"));

    AI_STATUS.set(503);
    AI_BODY.set("{\"error\":{\"code\":\"PROVIDER_UNAVAILABLE\"}}");
    ask(TestJwt.analyst(), question("wh-test-pg", "ds-test-pg"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.retryable").value(true));
  }

  @Test
  void sqlErrorFromWarehouseIsSanitized() throws Exception {
    AI_BODY.set(candidate("SELECT no_such_column FROM sales.orders"));
    ask(TestJwt.analyst(), question("wh-test-pg", "ds-test-pg"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error.code").value("QUERY_ERROR"))
        .andExpect(jsonPath("$.error.message").value("Kho dữ liệu không thực thi được truy vấn."));
  }

  // ---------------- Authorization and validation (no history row) ----------------

  @Test
  void viewerAndWrongWarehouseAreForbiddenWithoutHistory() throws Exception {
    long before = historyCount();
    ask(TestJwt.viewer(), question("wh-test-pg", "ds-test-pg"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    ask(TestJwt.analyst(), question("wh-test-other", "ds-test-other"))
        .andExpect(status().isForbidden());
    assertThat(historyCount()).isEqualTo(before);
  }

  @Test
  void adminMayQueryAnyWarehouse() throws Exception {
    ask(TestJwt.admin(), question("wh-test-other", "ds-test-other"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }

  @Test
  void invalidRequestsAreRejected() throws Exception {
    ask(TestJwt.analyst(), question("wh-test-pg", "ds-test-other"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    ask(TestJwt.analyst(), Map.of("warehouseId", "wh-test-pg", "datasetId", "ds-test-pg"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.details.parameter").value("question"));
    ask(
            TestJwt.analyst(),
            Map.of(
                "warehouseId", "wh-test-pg",
                "datasetId", "ds-test-pg",
                "question", "x",
                "sql", "SELECT 1"))
        .andExpect(status().isBadRequest());
    ask(
            TestJwt.analyst(),
            Map.of(
                "warehouseId", "wh-test-pg",
                "datasetId", "ds-test-pg",
                "question", "x",
                "mode", "MAGIC"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void queryRequiresToken() throws Exception {
    mvc.perform(
            post("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(question("wh-test-pg", "ds-test-pg"))))
        .andExpect(status().isUnauthorized());
  }

  // ---------------- Suggested questions ----------------

  @Test
  void examplesFollowTheDatasetDialectAndAccess() throws Exception {
    mvc.perform(
            get("/api/v1/datasets/ds-test-pg/examples")
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.analyst())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(2))
        .andExpect(jsonPath("$.items[0].sqlDialect").value("POSTGRESQL"))
        .andExpect(jsonPath("$.items[0].question").value("Doanh thu theo quốc gia?"));
    mvc.perform(
            get("/api/v1/datasets/ds-test-pg/examples?language=en")
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.analyst())))
        .andExpect(jsonPath("$.totalItems").value(1));
    mvc.perform(
            get("/api/v1/datasets/ds-test-other/examples")
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.analyst())))
        .andExpect(status().isForbidden());
  }
}
