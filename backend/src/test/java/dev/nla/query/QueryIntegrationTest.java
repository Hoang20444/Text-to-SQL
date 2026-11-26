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
            .andExpect(jsonPath("$.chart.x").value("country"))
            .andExpect(jsonPath("$.chart.series[0].column").value("total_amount"))
            .andExpect(jsonPath("$.chart.reason").doesNotExist())
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
  void chartThatDoesNotFitTheResultIsStoredAsTable() throws Exception {
    // The AI points the chart at total_amount but the SQL only returns order_id and country.
    AI.answer("SELECT order_id, country FROM sales.orders ORDER BY order_id");
    String body =
        ask(TestJwt.analyst(), question("pg"))
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.chart.type").value("table"))
            .andExpect(jsonPath("$.chart.series").isEmpty())
            .andExpect(jsonPath("$.chart.x").doesNotExist())
            .andExpect(
                jsonPath("$.chart.reason")
                    .value(org.hamcrest.Matchers.containsString("total_amount")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(
            get("/api/v1/query/" + Json.str(Json.read(body), "queryId"))
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.analyst())))
        .andExpect(jsonPath("$.chart.type").value("table"));
  }

  @Test
  void csvExportIsOwnerOnlyAndAudited() throws Exception {
    String body =
        ask(TestJwt.analyst(), question("pg")).andReturn().getResponse().getContentAsString();
    String url = "/api/v1/query/" + Json.str(Json.read(body), "queryId") + "/export.csv";
    String csv =
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.analyst())))
            .andExpect(status().isOk())
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                    .string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
            .andReturn()
            .getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(csv)
        .isEqualTo(
            "﻿\"country\",\"total_amount\"\r\n"
                + "\"JP\",\"10.00\"\r\n\"US\",\"100.00\"\r\n\"VN\",\"300.00\"\r\n");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'QUERY_CSV_EXPORT'",
                Integer.class))
        .isPositive();
    // The viewer has the warehouse grant but does not own the query.
    mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.viewer())))
        .andExpect(status().isForbidden());
  }

  @Test
  void singleNumberResultIsShownAsKpiCard() throws Exception {
    AI.answer("SELECT SUM(amount) AS total_amount FROM sales.orders");
    ask(TestJwt.analyst(), question("pg"))
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.chart.type").value("number"))
        .andExpect(jsonPath("$.chart.series[0].column").value("total_amount"));
  }

  private String askFor(String token, Map<String, Object> body) throws Exception {
    return Json.str(
        Json.read(ask(token, body).andReturn().getResponse().getContentAsString()), "queryId");
  }

  private static Map<String, Object> followUp(String suffix, String question, Object parentId) {
    var body = question(suffix);
    body.put("question", question);
    body.put("followUpQueryId", parentId);
    return body;
  }

  /** T-4.2.2: a short follow-up reaches the AI with the previous question and is linked. */
  @Test
  void followUpSendsPreviousQuestionAndLinksHistory() throws Exception {
    String first = askFor(TestJwt.analyst(), question("pg"));
    assertThat(Json.read(AI.lastRequest.get()).get("question"))
        .isEqualTo("Doanh thu theo quốc gia?");

    AI.answer(
        "SELECT country, SUM(amount) AS total_amount FROM sales.orders"
            + " GROUP BY country ORDER BY total_amount DESC LIMIT 2");
    ask(TestJwt.analyst(), followUp("pg", "Chỉ lấy 2 nước đứng đầu", first))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.question").value("Chỉ lấy 2 nước đứng đầu"))
        .andExpect(jsonPath("$.followUpQueryId").value(first))
        .andExpect(jsonPath("$.explanation").value("Tổng tiền theo quốc gia."))
        .andExpect(jsonPath("$.assumptions[0]").value("Số tiền theo USD."))
        .andExpect(jsonPath("$.sourceTables[0].name").value("orders"))
        .andExpect(jsonPath("$.result.rows.length()").value(2));

    var sent = Json.read(AI.lastRequest.get());
    // The format the AI service reads (semantic-workbench.md §5); no extra request field.
    assertThat(sent.get("question"))
        .isEqualTo(
            "Previous question context: Doanh thu theo quốc gia?\n"
                + "Follow-up question: Chỉ lấy 2 nước đứng đầu");
    assertThat(sent)
        .containsOnlyKeys(
            "correlationId",
            "warehouseId",
            "datasetId",
            "sqlDialect",
            "question",
            "semanticContext");
    // Neither the previous SQL nor its result rows reach the model.
    assertThat(Json.write(sent.get("question"))).doesNotContain("SELECT", "300.00");

    mvc.perform(
            get("/api/v1/query/history")
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.analyst())))
        .andExpect(
            jsonPath("$.items[?(@.queryId=='" + first + "')].followUpQueryId")
                .value(org.hamcrest.Matchers.contains((Object) null)))
        .andExpect(jsonPath("$.items[?(@.followUpQueryId=='" + first + "')]").isNotEmpty());
  }

  /** A third question keeps the filter of the second: the whole chain is the previous context. */
  @Test
  void followUpOfAFollowUpSendsTheWholeChain() throws Exception {
    String first = askFor(TestJwt.analyst(), question("pg"));
    AI.answer(
        "SELECT country, SUM(amount) AS total_amount FROM sales.orders WHERE is_paid"
            + " GROUP BY country ORDER BY country");
    String second = askFor(TestJwt.analyst(), followUp("pg", "Chỉ đơn đã thanh toán", first));
    AI.answer(
        "SELECT country, SUM(amount) AS total_amount FROM sales.orders WHERE is_paid"
            + " GROUP BY country ORDER BY total_amount DESC LIMIT 1");
    ask(TestJwt.analyst(), followUp("pg", "Nước cao nhất", second))
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.followUpQueryId").value(second));
    assertThat(Json.read(AI.lastRequest.get()).get("question"))
        .isEqualTo(
            "Previous question context: Doanh thu theo quốc gia?; Chỉ đơn đã thanh toán\n"
                + "Follow-up question: Nước cao nhất");
    // History keeps exactly what the user typed, not the composed text.
    mvc.perform(
            get("/api/v1/query/" + second)
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.analyst())))
        .andExpect(jsonPath("$.question").value("Chỉ đơn đã thanh toán"));
  }

  @Test
  void longChainsDropTheOldestQuestionsAndTooLongFollowUpsAreRejected() throws Exception {
    String longText = "Doanh thu theo quốc gia và thể loại ".repeat(15).strip();
    var opening = question("pg");
    opening.put("question", longText);
    String first = askFor(TestJwt.analyst(), opening);
    String second = askFor(TestJwt.analyst(), followUp("pg", "Chỉ năm 2026", first));
    ask(TestJwt.analyst(), followUp("pg", "x".repeat(500), second))
        .andExpect(jsonPath("$.status").value("SUCCESS"));
    // The long opening question no longer fits, so only the direct parent stays.
    assertThat(Json.read(AI.lastRequest.get()).get("question"))
        .isEqualTo(
            "Previous question context: Chỉ năm 2026\nFollow-up question: " + "x".repeat(500));

    int before = historyCount();
    ask(TestJwt.analyst(), followUp("pg", "y".repeat(1000), first))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    assertThat(historyCount()).isEqualTo(before);
  }

  @Test
  void invalidFollowUpsAreRejectedWithoutHistory() throws Exception {
    String own = askFor(TestJwt.analyst(), question("pg"));
    String adminsOther = askFor(TestJwt.admin(), question("other"));
    String adminsPg = askFor(TestJwt.admin(), question("pg"));
    AI.answer("DELETE FROM sales.orders");
    String rejected = askFor(TestJwt.analyst(), question("pg"));
    int before = historyCount();

    ask(TestJwt.analyst(), followUp("pg", "Top 5", "qry-missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    // Another user's query cannot be continued by the analyst.
    ask(TestJwt.analyst(), followUp("pg", "Top 5", adminsPg))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    ask(TestJwt.admin(), followUp("pg", "Top 5", adminsOther))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    ask(TestJwt.analyst(), followUp("pg", "Top 5", rejected))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    ask(TestJwt.analyst(), followUp("pg", "Top 5", 42)).andExpect(status().isBadRequest());
    ask(TestJwt.analyst(), followUp("pg", "Top 5", " ")).andExpect(status().isBadRequest());
    assertThat(historyCount()).isEqualTo(before);

    // ADMIN may continue any query it can see, like GET /query/{id}.
    AI.answer("SELECT country FROM sales.orders GROUP BY country ORDER BY country");
    ask(TestJwt.admin(), followUp("pg", "Chỉ tên nước", own))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.followUpQueryId").value(own));
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
