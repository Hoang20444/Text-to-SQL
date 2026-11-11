package dev.nla.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.nla.common.Json;
import dev.nla.store.Store;
import dev.nla.support.FakeAi;
import dev.nla.support.TestJwt;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-1.3.2: POST /api/v1/query -> authorization -> AI candidate -> Java read-only check -> real
 * PostgreSQL warehouse through a SELECT-only reader -> normalized result and query history.
 */
@SpringBootTest(
    properties = {
      "spring.config.import=",
      "nla.local-bootstrap=false",
      "nla.warehouse-tls=false",
      "OIDC_ISSUER_URI=" + TestJwt.ISSUER,
      "OIDC_AUDIENCE=" + TestJwt.AUDIENCE,
      "PYTHON_AI_TOKEN=" + FakeAi.TOKEN,
      "TEST_WH_READER_PASSWORD=reader-secret-123"
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(TestJwt.DecoderConfig.class)
class QueryIntegrationTest {
  @Container @ServiceConnection
  static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

  static final FakeAi AI = new FakeAi();

  @DynamicPropertySource
  static void ai(DynamicPropertyRegistry registry) {
    registry.add("PYTHON_AI_URL", AI::url);
    registry.add("WAREHOUSE_ALLOWED_HOSTS", () -> db.getHost());
  }

  @AfterAll
  static void stopAi() {
    AI.stop();
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void seed() {
    jdbc.execute(
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
        """);
    jdbc.update("DELETE FROM resource_access");
    jdbc.update("DELETE FROM query_history");
    jdbc.update("DELETE FROM dataset");
    jdbc.update("DELETE FROM warehouse");
    var store = new Store(jdbc);
    for (String id : List.of("pg", "other")) {
      store.insertWarehouse(
          Json.obj(
              "warehouseId",
              "wh-test-" + id,
              "name",
              "Test " + id,
              "engine",
              "POSTGRESQL",
              "host",
              db.getHost(),
              "port",
              db.getMappedPort(5432),
              "database",
              db.getDatabaseName(),
              "defaultSchema",
              "sales",
              "username",
              "wh_reader",
              "status",
              "ACTIVE"),
          "env:TEST_WH_READER_PASSWORD");
      store.insertDataset(
          Json.obj(
              "datasetId",
              "ds-test-" + id,
              "warehouseId",
              "wh-test-" + id,
              "name",
              "Sales",
              "allowedSchemas",
              List.of("sales"),
              "allowedTables",
              List.of(Json.obj("schema", "sales", "name", "orders")),
              "semanticVersion",
              "1.0.0",
              "schemaVersion",
              "test-1",
              "status",
              "ACTIVE"),
          Json.read(FakeAi.SEMANTIC_MODEL));
    }
    store.grantWarehouseAccess("wh-test-pg", TestJwt.ANALYST_SUB);
    store.grantWarehouseAccess("wh-test-pg", TestJwt.VIEWER_SUB);
    AI.answer(
        "SELECT country, SUM(amount) AS total_amount FROM sales.orders"
            + " GROUP BY country ORDER BY country");
  }

  private ResultActions ask(String token, Map<String, Object> body) throws Exception {
    return mvc.perform(
        post("/api/v1/query")
            .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(Json.write(body)));
  }

  private static Map<String, Object> question(String suffix) {
    return Json.obj(
        "warehouseId",
        "wh-test-" + suffix,
        "datasetId",
        "ds-test-" + suffix,
        "question",
        "Doanh thu theo quốc gia?",
        "mode",
        "DETERMINISTIC");
  }

  private int historyCount() {
    return jdbc.queryForObject("SELECT count(*) FROM query_history", Integer.class);
  }

  @Test
  void runsReadOnlyQueryAndStoresHistory() throws Exception {
    String body =
        ask(TestJwt.analyst(), question("pg"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.engine").value("POSTGRESQL"))
            .andExpect(jsonPath("$.executionMode").value("DETERMINISTIC"))
            .andExpect(jsonPath("$.model").value("deterministic-semantic-examples"))
            .andExpect(jsonPath("$.generatedSql").value(org.hamcrest.Matchers.startsWith("SELECT")))
            .andExpect(jsonPath("$.latencyMs").isNumber())
            .andExpect(jsonPath("$.result.columns[0].name").value("country"))
            .andExpect(jsonPath("$.result.columns[0].semanticRole").value("DIMENSION"))
            .andExpect(jsonPath("$.result.columns[1].dataType").value("DECIMAL"))
            .andExpect(jsonPath("$.result.rows[*].country", contains("JP", "US", "VN")))
            .andExpect(jsonPath("$.result.rows[2].total_amount").value("300.00"))
            .andExpect(jsonPath("$.result.truncated").value(false))
            .andExpect(jsonPath("$.chart.type").value("bar"))
            .andExpect(jsonPath("$.error").isEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String queryId = Json.str(Json.read(body), "queryId");
    mvc.perform(
            get("/api/v1/query/" + queryId)
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.analyst())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
    mvc.perform(
            get("/api/v1/query/history")
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.analyst())))
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].queryId").value(queryId))
        .andExpect(jsonPath("$.items[0].rowCount").value(3));
    // Another user does not see the analyst's query.
    mvc.perform(
            get("/api/v1/query/" + queryId)
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.viewer())))
        .andExpect(status().isForbidden());
  }

  @Test
  void sendsSchemaContextToAiButNeverCredentials() throws Exception {
    ask(TestJwt.analyst(), question("pg")).andExpect(status().isCreated());
    String sent = AI.lastRequest.get();
    assertThat(sent).doesNotContain("reader-secret-123", "TEST_WH_READER_PASSWORD", "wh_reader");
    assertThat(AI.lastMode.get()).isEqualTo("DETERMINISTIC");
    assertThat(AI.lastAuth.get()).isEqualTo("Bearer " + FakeAi.TOKEN);
    var request = Json.read(sent);
    assertThat(request.get("sqlDialect")).isEqualTo("POSTGRESQL");
    var context = Json.map(request.get("semanticContext"));
    var tables = Json.objects(context.get("tables"));
    assertThat(tables).hasSize(1);
    assertThat(tables.getFirst().get("name")).isEqualTo("orders");
    assertThat(Json.objects(tables.getFirst().get("columns"))).hasSize(6);
    assertThat(context.get("timezone")).isEqualTo("Asia/Ho_Chi_Minh");
  }

  @Test
  void convertsTypes() throws Exception {
    AI.answer("SELECT order_id, placed_at, is_paid, score FROM sales.orders ORDER BY order_id");
    ask(TestJwt.analyst(), question("pg"))
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.result.returnedRowCount").value(5))
        .andExpect(jsonPath("$.result.rows[0].order_id").value("1"))
        .andExpect(jsonPath("$.result.columns[1].dataType").value("TIMESTAMP"))
        .andExpect(jsonPath("$.result.rows[0].is_paid").value(true))
        .andExpect(jsonPath("$.result.rows[0].score").value(1.5));
  }

  @Test
  void writeSqlFromAiIsRejectedBeforeExecution() throws Exception {
    AI.answer("DELETE FROM sales.orders");
    ask(TestJwt.analyst(), question("pg"))
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.error.code").value("SQL_SECURITY_ERROR"))
        .andExpect(jsonPath("$.error.details.queryId").exists());
    AI.answer("SELECT 1; DROP TABLE sales.orders");
    ask(TestJwt.analyst(), question("pg"))
        .andExpect(jsonPath("$.error.code").value("SQL_SECURITY_ERROR"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.orders", Integer.class))
        .isEqualTo(5);
  }

  @Test
  void aiRejectionAndBrokenCandidateAreRecorded() throws Exception {
    AI.status.set(422);
    AI.body.set("{\"error\":{\"code\":\"UNSUPPORTED_QUESTION\",\"message\":\"x\"}}");
    ask(TestJwt.analyst(), question("pg"))
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_QUESTION"));
    AI.status.set(200);
    AI.body.set("{\"sql\":\"SELECT 1\"}");
    ask(TestJwt.analyst(), question("pg"))
        .andExpect(jsonPath("$.status").value("QUERY_ERROR"))
        .andExpect(jsonPath("$.error.code").value("LLM_ERROR"));
  }

  @Test
  void guardRejectsUnknownColumnsAndOtherTables() throws Exception {
    AI.answer("SELECT no_such_column FROM sales.orders");
    ask(TestJwt.analyst(), question("pg"))
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.error.code").value("SQL_SECURITY_ERROR"));
    AI.answer("SELECT id FROM public.warehouse");
    ask(TestJwt.analyst(), question("pg"))
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.error.code").value("SQL_SECURITY_ERROR"));
  }

  @Test
  void sourceTablesAreRecorded() throws Exception {
    ask(TestJwt.analyst(), question("pg"))
        .andExpect(jsonPath("$.sourceTables[0].schema").value("sales"))
        .andExpect(jsonPath("$.sourceTables[0].name").value("orders"));
  }

  @Test
  void warehouseErrorIsSanitized() throws Exception {
    AI.answer("SELECT country, SUM(amount) / 0 AS ratio FROM sales.orders GROUP BY country");
    String body =
        ask(TestJwt.analyst(), question("pg"))
            .andExpect(jsonPath("$.status").value("QUERY_ERROR"))
            .andExpect(jsonPath("$.error.code").value("QUERY_ERROR"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("division by zero", "PSQLException");
  }

  @Test
  void viewerAndWrongWarehouseAreForbiddenWithoutHistory() throws Exception {
    int before = historyCount();
    ask(TestJwt.viewer(), question("pg"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    ask(TestJwt.analyst(), question("other")).andExpect(status().isForbidden());
    assertThat(historyCount()).isEqualTo(before);
  }

  @Test
  void adminMayQueryAnyWarehouse() throws Exception {
    ask(TestJwt.admin(), question("other"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
  }

  @Test
  void invalidRequestsAreRejected() throws Exception {
    var mismatched = question("pg");
    mismatched.put("datasetId", "ds-test-other");
    ask(TestJwt.admin(), mismatched)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    ask(TestJwt.analyst(), Json.obj("warehouseId", "wh-test-pg", "datasetId", "ds-test-pg"))
        .andExpect(status().isBadRequest());
    var smuggled = question("pg");
    smuggled.put("sql", "SELECT 1");
    ask(TestJwt.analyst(), smuggled).andExpect(status().isBadRequest());
    var badMode = question("pg");
    badMode.put("mode", "MAGIC");
    ask(TestJwt.analyst(), badMode).andExpect(status().isBadRequest());
  }

  @Test
  void queryRequiresToken() throws Exception {
    mvc.perform(
            post("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(Json.write(question("pg"))))
        .andExpect(status().isUnauthorized());
  }
}
