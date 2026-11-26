package dev.nla.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.util.concurrent.CountDownLatch;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-4.1.2: the dataset knowledge API on a real PostgreSQL warehouse, with the Python knowledge
 * store replaced by {@link FakeAi}. Covers status (including AI down and a running sync), approved
 * examples checked by the SQL guard, delete, background reindex and search.
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
class KnowledgeIntegrationTest {
  @Container @ServiceConnection
  static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

  static final FakeAi AI = new FakeAi();
  static final String BASE = "/api/v1/datasets/ds-test-pg/knowledge";
  static final String GOOD_SQL =
      "SELECT country, SUM(amount) AS total_amount FROM sales.orders GROUP BY country";

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
  void seed() throws Exception {
    jdbc.execute(
        """
        CREATE SCHEMA IF NOT EXISTS sales;
        CREATE TABLE IF NOT EXISTS sales.orders (
          order_id bigint PRIMARY KEY, country varchar(50) NOT NULL,
          amount numeric(10,2) NOT NULL);
        DO $$ BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wh_reader') THEN
            CREATE ROLE wh_reader LOGIN PASSWORD 'reader-secret-123';
          END IF;
        END $$;
        GRANT USAGE ON SCHEMA sales TO wh_reader;
        GRANT SELECT ON sales.orders TO wh_reader;
        """);
    // Let a sync from the previous test finish before its rows are removed.
    awaitIdle();
    jdbc.update("DELETE FROM knowledge_example");
    jdbc.update("DELETE FROM operation_run WHERE kind = 'KNOWLEDGE'");
    jdbc.update("DELETE FROM resource_access");
    jdbc.update("DELETE FROM query_history");
    jdbc.update("DELETE FROM dataset");
    jdbc.update("DELETE FROM warehouse");
    var store = new Store(jdbc);
    var semantic = Json.read(FakeAi.SEMANTIC_MODEL);
    semantic.put(
        "examples",
        List.of(
            Json.obj(
                "question",
                "Tổng tiền theo quốc gia?",
                "sql",
                GOOD_SQL,
                "sqlDialect",
                "POSTGRESQL",
                "selectedMetrics",
                List.of("total_amount"),
                "selectedDimensions",
                List.of("country")),
            Json.obj(
                "question",
                "MySQL only",
                "sql",
                "SELECT `country` FROM sales.orders",
                "sqlDialect",
                "MYSQL",
                "selectedMetrics",
                List.of(),
                "selectedDimensions",
                List.of())));
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
          semantic);
    }
    store.grantWarehouseAccess("wh-test-pg", TestJwt.ANALYST_SUB);
    store.grantWarehouseAccess("wh-test-pg", TestJwt.VIEWER_SUB);
    AI.resetKnowledge();
  }

  private ResultActions call(String token, MockHttpServletRequestBuilder request, Object body)
      throws Exception {
    request.header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(token));
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(Json.write(body));
    return mvc.perform(request);
  }

  private Map<String, Object> json(ResultActions actions) throws Exception {
    return Json.read(actions.andReturn().getResponse().getContentAsString());
  }

  private Map<String, Object> addExample(String question, String sql) throws Exception {
    return json(
        call(
            TestJwt.admin(), post(BASE + "/examples"), Json.obj("question", question, "sql", sql)));
  }

  /** Waits for the background sync of the current dataset, then returns the finished run. */
  private Map<String, Object> awaitRun() throws Exception {
    for (int i = 0; i < 200; i++) {
      var last = new Store(jdbc).latestRun("KNOWLEDGE", "ds-test-pg");
      if (last != null && !"RUNNING".equals(last.get("status"))) return last;
      Thread.sleep(100);
    }
    throw new AssertionError("knowledge sync did not finish");
  }

  private void awaitIdle() throws Exception {
    for (int i = 0; i < 200; i++) {
      Integer running =
          jdbc.queryForObject(
              "SELECT count(*) FROM operation_run WHERE kind = 'KNOWLEDGE'"
                  + " AND document->>'status' = 'RUNNING'",
              Integer.class);
      if (running == null || running == 0) return;
      Thread.sleep(100);
    }
  }

  @Test
  void statusComesFromTheAiIndexAndEveryRoleWithAccessCanRead() throws Exception {
    addExample("Doanh thu theo quốc gia", GOOD_SQL);
    call(TestJwt.viewer(), get(BASE), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.datasetId").value("ds-test-pg"))
        .andExpect(jsonPath("$.status").value("READY"))
        .andExpect(jsonPath("$.schemaItems").value(7))
        .andExpect(jsonPath("$.exampleCount").value(1))
        .andExpect(jsonPath("$.approvedExampleCount").value(1))
        .andExpect(jsonPath("$.embeddingModel").value("embeddinggemma:300m"))
        .andExpect(jsonPath("$.semanticVersion").value("1.0.0"))
        .andExpect(jsonPath("$.error").isEmpty())
        .andExpect(jsonPath("$.lastRun").isEmpty());
    // Java sends the dataset context, never credentials.
    String sent = AI.knowledgeLastBody.get("status");
    assertThat(sent).contains("\"sqlDialect\":\"POSTGRESQL\"", "\"semanticContext\"");
    assertThat(sent).doesNotContain("reader-secret-123", "wh_reader");
    call(TestJwt.analyst(), get("/api/v1/datasets/ds-test-other/knowledge"), null)
        .andExpect(status().isForbidden());
  }

  @Test
  void statusStillAnswersWhenTheAiServiceIsDown() throws Exception {
    AI.knowledgeDown.set(true);
    call(TestJwt.analyst(), get(BASE), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ERROR"))
        .andExpect(jsonPath("$.error").isNotEmpty())
        .andExpect(jsonPath("$.embeddingModel").value("unknown"))
        .andExpect(jsonPath("$.schemaItems").value(0));
    // Examples are Java's own records and stay readable.
    call(TestJwt.analyst(), get(BASE + "/examples"), null).andExpect(status().isOk());
  }

  @Test
  void approvedExamplesAreCheckedStoredAndIndexedAtOnce() throws Exception {
    var created =
        call(
                TestJwt.admin(),
                post(BASE + "/examples"),
                Json.obj("question", "  Doanh thu theo quốc gia  ", "sql", GOOD_SQL))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.indexed").value(true))
            .andExpect(
                jsonPath("$.example.entryId").value(org.hamcrest.Matchers.startsWith("kex-")))
            .andExpect(jsonPath("$.example.question").value("Doanh thu theo quốc gia"))
            .andExpect(jsonPath("$.example.sqlDialect").value("POSTGRESQL"))
            .andExpect(jsonPath("$.example.source").value("approved-query"));
    String entryId = Json.str(Json.map(json(created).get("example")), "entryId");
    assertThat(AI.knowledgeEntries).hasSize(1);
    assertThat(AI.knowledgeLastBody.get("remember")).contains("Doanh thu theo quốc gia");

    // The same pair again is not duplicated.
    call(
            TestJwt.admin(),
            post(BASE + "/examples"),
            Json.obj("question", "Doanh thu theo quốc gia", "sql", GOOD_SQL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.example.entryId").value(entryId));

    call(TestJwt.viewer(), get(BASE + "/examples"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.items[0].entryId").value(entryId))
        .andExpect(jsonPath("$.items[0].createdBy").isNotEmpty())
        .andExpect(jsonPath("$.items[1].entryId").value("semantic-0"))
        .andExpect(jsonPath("$.items[1].source").value("semantic-config"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'KNOWLEDGE_EXAMPLE_ADD'",
                Integer.class))
        .isPositive();
  }

  @Test
  void unsafeOrForeignSqlIsRejectedAndNothingIsStored() throws Exception {
    call(
            TestJwt.admin(),
            post(BASE + "/examples"),
            Json.obj("question", "Xóa", "sql", "DELETE FROM sales.orders"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("SQL_SECURITY_ERROR"));
    call(
            TestJwt.admin(),
            post(BASE + "/examples"),
            Json.obj("question", "Bảng hệ thống", "sql", "SELECT id FROM public.warehouse"))
        .andExpect(status().isUnprocessableEntity());
    call(
            TestJwt.admin(),
            post(BASE + "/examples"),
            Json.obj("question", "x", "sql", GOOD_SQL, "datasetId", "ds-test-other"))
        .andExpect(status().isBadRequest());
    call(TestJwt.admin(), post(BASE + "/examples"), Json.obj("question", " ", "sql", GOOD_SQL))
        .andExpect(status().isBadRequest());
    call(
            TestJwt.analyst(),
            post(BASE + "/examples"),
            Json.obj("question", "Doanh thu", "sql", GOOD_SQL))
        .andExpect(status().isForbidden());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_example", Integer.class))
        .isZero();
    assertThat(AI.knowledgeCalls).doesNotContain("remember");
  }

  @Test
  void examplesAreKeptWhenTheIndexIsNotReady() throws Exception {
    AI.knowledgeState.set("STALE");
    call(
            TestJwt.admin(),
            post(BASE + "/examples"),
            Json.obj("question", "Doanh thu theo quốc gia", "sql", GOOD_SQL))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.indexed").value(false));
    AI.knowledgeDown.set(true);
    call(
            TestJwt.admin(),
            post(BASE + "/examples"),
            Json.obj("question", "Tổng tiền", "sql", GOOD_SQL))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.indexed").value(false));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_example", Integer.class))
        .isEqualTo(2);
    assertThat(AI.knowledgeCalls).doesNotContain("remember");
  }

  @Test
  void deleteRemovesTheExampleFromTheIndexFirst() throws Exception {
    String entryId =
        Json.str(
            Json.map(addExample("Doanh thu theo quốc gia", GOOD_SQL).get("example")), "entryId");
    assertThat(AI.knowledgeEntries).hasSize(1);

    // AI down while the index may hold it: refuse, keep the example.
    AI.knowledgeDown.set(true);
    call(TestJwt.admin(), delete(BASE + "/examples/" + entryId), null)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("KNOWLEDGE_UNAVAILABLE"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_example", Integer.class))
        .isEqualTo(1);

    AI.knowledgeDown.set(false);
    call(TestJwt.analyst(), delete(BASE + "/examples/" + entryId), null)
        .andExpect(status().isForbidden());
    call(TestJwt.admin(), delete(BASE + "/examples/" + entryId), null)
        .andExpect(status().isNoContent());
    assertThat(AI.knowledgeEntries).isEmpty();
    assertThat(AI.knowledgeCalls).contains("forget");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_example", Integer.class))
        .isZero();

    call(TestJwt.admin(), delete(BASE + "/examples/" + entryId), null)
        .andExpect(status().isNotFound());
    call(TestJwt.admin(), delete(BASE + "/examples/semantic-0"), null)
        .andExpect(status().isBadRequest());
  }

  @Test
  void deleteWhileStaleOnlyRemovesJavasRecord() throws Exception {
    String entryId =
        Json.str(
            Json.map(addExample("Doanh thu theo quốc gia", GOOD_SQL).get("example")), "entryId");
    AI.knowledgeState.set("STALE");
    call(TestJwt.admin(), delete(BASE + "/examples/" + entryId), null)
        .andExpect(status().isNoContent());
    assertThat(AI.knowledgeCalls).doesNotContain("forget");
  }

  @Test
  void reindexRunsInTheBackgroundAndResendsApprovedExamples() throws Exception {
    addExample("Doanh thu theo quốc gia", GOOD_SQL);
    // A stored example the current schema no longer accepts (column was dropped).
    new Store(jdbc)
        .insertKnowledgeExample(
            Json.obj(
                "entryId",
                "kex-old",
                "datasetId",
                "ds-test-pg",
                "warehouseId",
                "wh-test-pg",
                "question",
                "Cột cũ",
                "sql",
                "SELECT discount FROM sales.orders",
                "sqlDialect",
                "POSTGRESQL",
                "source",
                "approved-query",
                "createdAt",
                "2026-11-01T00:00:00Z",
                "createdBy",
                "admin"));
    // An approval left in the index for an example Java no longer has.
    AI.knowledgeEntries.add(
        Json.obj(
            "entryId",
            "deadbeef",
            "question",
            "Đã xóa",
            "sql",
            "SELECT 1",
            "sqlDialect",
            "POSTGRESQL",
            "source",
            "approved-query"));
    AI.knowledgeState.set("STALE");
    var gate = new CountDownLatch(1);
    AI.indexGate.set(gate);

    var started =
        json(
            call(TestJwt.admin(), post(BASE + "/reindex"), null)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.trigger").value("MANUAL")));
    // While it runs the screen shows INDEXING, and a second click returns the same run.
    call(TestJwt.viewer(), get(BASE), null)
        .andExpect(jsonPath("$.status").value("INDEXING"))
        .andExpect(jsonPath("$.lastRun.runId").value(started.get("runId")));
    call(TestJwt.admin(), post(BASE + "/reindex"), null)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.runId").value(started.get("runId")));
    gate.countDown();

    var run = awaitRun();
    assertThat(run.get("runId")).isEqualTo(started.get("runId"));
    assertThat(run.get("status")).isEqualTo("PARTIAL");
    assertThat(run.get("rememberedCount")).isEqualTo(1);
    assertThat(Json.objects(run.get("skippedExamples")))
        .singleElement()
        .satisfies(skipped -> assertThat(skipped.get("entryId")).isEqualTo("kex-old"));
    assertThat(run.get("completedAt")).isNotNull();
    assertThat(AI.knowledgeEntries)
        .extracting(e -> e.get("question"))
        .containsExactly("Doanh thu theo quốc gia");
    call(TestJwt.viewer(), get(BASE), null)
        .andExpect(jsonPath("$.status").value("READY"))
        .andExpect(jsonPath("$.lastRun.status").value("PARTIAL"));
    call(TestJwt.analyst(), post(BASE + "/reindex"), null).andExpect(status().isForbidden());
  }

  @Test
  void failedIndexKeepsTheRunReadable() throws Exception {
    AI.indexFails.set(true);
    call(TestJwt.admin(), post(BASE + "/reindex"), null).andExpect(status().isAccepted());
    var run = awaitRun();
    assertThat(run.get("status")).isEqualTo("FAILED");
    assertThat((String) run.get("error")).isNotBlank();
    AI.indexFails.set(false);
    call(TestJwt.admin(), post(BASE + "/reindex"), null)
        .andExpect(jsonPath("$.status").value("RUNNING"));
    assertThat(awaitRun().get("status")).isEqualTo("SUCCESS");
  }

  @Test
  void interruptedRunIsClosedAfterFifteenMinutes() throws Exception {
    new Store(jdbc)
        .run(
            "krun-stuck",
            "KNOWLEDGE",
            Json.obj(
                "runId",
                "krun-stuck",
                "datasetId",
                "ds-test-pg",
                "warehouseId",
                "wh-test-pg",
                "trigger",
                "MANUAL",
                "status",
                "RUNNING",
                "startedAt",
                "2026-01-01T00:00:00Z"));
    call(TestJwt.viewer(), get(BASE), null)
        .andExpect(jsonPath("$.status").value("READY"))
        .andExpect(jsonPath("$.lastRun.status").value("FAILED"));
  }

  @Test
  void searchIsForwardedAndValidated() throws Exception {
    call(TestJwt.viewer(), post(BASE + "/search"), Json.obj("question", "doanh thu", "limit", 5))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.strategy").value("vector"))
        .andExpect(jsonPath("$.hits[0].name").value("sales.orders"));
    assertThat(AI.knowledgeLastBody.get("search")).contains("\"limit\":5", "doanh thu");
    call(TestJwt.viewer(), post(BASE + "/search"), Json.obj("question", "x", "limit", 21))
        .andExpect(status().isBadRequest());
    call(TestJwt.viewer(), post(BASE + "/search"), Json.obj("question", ""))
        .andExpect(status().isBadRequest());
    AI.knowledgeState.set("EMPTY");
    call(TestJwt.viewer(), post(BASE + "/search"), Json.obj("question", "doanh thu"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("KNOWLEDGE_UNAVAILABLE"));
  }
}
