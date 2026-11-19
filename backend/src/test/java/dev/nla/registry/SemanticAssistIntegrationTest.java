package dev.nla.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.nla.common.Json;
import dev.nla.support.FakeAi;
import dev.nla.support.TestJwt;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-3.2.2 on a real PostgreSQL warehouse with a fake AI service: the draft is checked against live
 * tables, compared with existing definitions, returned as a preview and never saved; AI failures
 * give controlled errors while the manual editor keeps working.
 */
@SpringBootTest(
    properties = {
      "spring.config.import=",
      "nla.local-bootstrap=false",
      "nla.warehouse-tls=false",
      "OIDC_ISSUER_URI=" + TestJwt.ISSUER,
      "OIDC_AUDIENCE=" + TestJwt.AUDIENCE,
      "NLA_MYSQL_RSA_PUBLIC_KEY=",
      "PYTHON_AI_TOKEN=" + FakeAi.TOKEN
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(TestJwt.DecoderConfig.class)
class SemanticAssistIntegrationTest {
  @Container @ServiceConnection
  static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

  static final FakeAi AI = new FakeAi();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("WAREHOUSE_ALLOWED_HOSTS", () -> String.join(",", pg.getHost(), "localhost"));
    registry.add("TEST_PG_PASSWORD", pg::getPassword);
    registry.add("PYTHON_AI_URL", AI::url);
  }

  @AfterAll
  static void stopAi() {
    AI.stop();
  }

  @Autowired MockMvc mvc;

  private String datasetId;

  @BeforeAll
  static void createShopSchema() throws Exception {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Statement s = c.createStatement()) {
      s.execute("CREATE SCHEMA shop");
      s.execute(
          "CREATE TABLE shop.customer (customer_id integer PRIMARY KEY,"
              + " country varchar(60) NOT NULL)");
      s.execute(
          "CREATE TABLE shop.orders (order_id bigint PRIMARY KEY, customer_id integer NOT NULL"
              + " REFERENCES shop.customer(customer_id), total numeric(10,2),"
              + " ordered_at timestamp)");
    }
  }

  @BeforeEach
  void registerDataset() throws Exception {
    // The AI service reports a configured environment model, like a real deployment.
    AI.providerStatus.set(
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
    var warehouse =
        json(
            as(
                    TestJwt.admin(),
                    post("/api/v1/warehouses"),
                    Json.obj(
                        "name",
                        "Shop assist " + System.nanoTime(),
                        "engine",
                        "POSTGRESQL",
                        "host",
                        pg.getHost(),
                        "port",
                        pg.getMappedPort(5432),
                        "database",
                        pg.getDatabaseName(),
                        "defaultSchema",
                        "shop",
                        "username",
                        pg.getUsername(),
                        "credentialReference",
                        "env:TEST_PG_PASSWORD"))
                .andExpect(status().isCreated()));
    as(TestJwt.admin(), post("/api/v1/warehouses/" + warehouse.get("warehouseId") + "/test"), null)
        .andExpect(jsonPath("$.reachable").value(true));
    var dataset =
        json(
            as(
                    TestJwt.admin(),
                    post("/api/v1/datasets"),
                    Json.obj(
                        "warehouseId",
                        warehouse.get("warehouseId"),
                        "name",
                        "Shop",
                        "allowedSchemas",
                        List.of("shop"),
                        "allowedTables",
                        List.of(
                            Json.obj("schema", "shop", "name", "customer"),
                            Json.obj("schema", "shop", "name", "orders"))))
                .andExpect(status().isCreated()));
    datasetId = Json.str(dataset, "datasetId");
  }

  private ResultActions as(String token, MockHttpServletRequestBuilder request, Object body)
      throws Exception {
    request.header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(token));
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(Json.write(body));
    return mvc.perform(request);
  }

  private static Map<String, Object> json(ResultActions result) throws Exception {
    return Json.read(result.andReturn().getResponse().getContentAsString());
  }

  private String url() {
    return "/api/v1/datasets/" + datasetId + "/semantic-assist";
  }

  private static Map<String, Object> table(String name) {
    return Json.obj("schema", "shop", "name", name);
  }

  private static Map<String, Object> dimension(String name, String table, String column) {
    return Json.obj(
        "name",
        name,
        "description",
        "Gợi ý " + name,
        "table",
        table(table),
        "column",
        column,
        "dataType",
        "VARCHAR",
        "synonyms",
        List.of());
  }

  private static Map<String, Object> metric(String name, String expression, List<String> dims) {
    return Json.obj(
        "name",
        name,
        "description",
        "Gợi ý " + name,
        "expression",
        expression,
        "unit",
        "",
        "tableRefs",
        List.of(table("orders")),
        "compatibleDimensions",
        dims,
        "synonyms",
        List.of("doanh thu"));
  }

  /** A draft with one good metric, one made-up column, one good and one made-up dimension. */
  private static String draft() {
    return Json.write(
        Json.obj(
            "model",
            "qwen2.5-coder:7b",
            "metrics",
            List.of(
                metric("revenue", "SUM(orders.total)", List.of("country")),
                metric("profit", "SUM(orders.profit)", List.of())),
            "dimensions",
            List.of(
                dimension("country", "customer", "country"), dimension("city", "customer", "city")),
            "businessRules",
            List.of("Doanh thu tính theo tổng tiền đơn hàng."),
            "warnings",
            List.of("Đây là bản nháp; quản trị viên cần xác nhận trước khi kích hoạt.")));
  }

  @Test
  void draftIsCheckedAndReturnedAsPreviewWithoutSaving() throws Exception {
    AI.assistStatus.set(200);
    AI.assistBody.set(draft());

    var preview =
        json(
            as(TestJwt.admin(), post(url()), Json.obj())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("qwen2.5-coder:7b"))
                .andExpect(jsonPath("$.baseSemanticVersion").value("0.0.0"))
                .andExpect(jsonPath("$.suggestedSemanticVersion").value("1.0.0"))
                .andExpect(jsonPath("$.businessRules[0].status").value("NEW"))
                .andExpect(jsonPath("$.warnings.length()").value(1)));
    var metrics = Json.objects(preview.get("metrics"));
    assertThat(metrics).extracting(m -> m.get("status")).containsExactly("NEW", "INVALID");
    assertThat(Json.str(metrics.get(1), "reason")).contains("profit");
    var dimensions = Json.objects(preview.get("dimensions"));
    assertThat(dimensions).extracting(d -> d.get("status")).containsExactly("NEW", "INVALID");

    // Only metadata went to the AI, with the default instruction and no SQL examples.
    var sent = Json.read(AI.lastAssistRequest.get());
    assertThat(sent.get("sqlDialect")).isEqualTo("POSTGRESQL");
    assertThat(Json.str(sent, "instruction")).contains("chỉ số kinh doanh");
    assertThat(Json.map(sent.get("semanticContext")).get("examples")).isEqualTo(List.of());
    assertThat(AI.lastAssistRequest.get()).doesNotContain("TEST_PG_PASSWORD");

    // Nothing was saved.
    as(TestJwt.admin(), get("/api/v1/datasets/" + datasetId + "/semantic-model"), null)
        .andExpect(jsonPath("$.semanticVersion").value("0.0.0"))
        .andExpect(jsonPath("$.metrics").isEmpty());
  }

  @Test
  void existingDefinitionsAreComparedNotOverwritten() throws Exception {
    String model = "/api/v1/datasets/" + datasetId + "/semantic-model";
    var current = new java.util.LinkedHashMap<>(json(as(TestJwt.admin(), get(model), null)));
    current.put("semanticVersion", "1.0.0");
    current.put("dimensions", List.of(dimension("country", "customer", "country")));
    current.put("metrics", List.of(metric("revenue", "SUM(orders.total)", List.of("country"))));
    as(
            TestJwt.admin(),
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(model),
            current)
        .andExpect(status().isOk());

    AI.assistStatus.set(200);
    AI.assistBody.set(
        Json.write(
            Json.obj(
                "model",
                "qwen2.5-coder:7b",
                "metrics",
                List.of(
                    metric("revenue", "SUM(orders.total) * 0.9", List.of("country")),
                    metric("order_count", "COUNT(*)", List.of("country"))),
                "dimensions",
                List.of(dimension("country", "customer", "country")),
                "businessRules",
                List.of(),
                "warnings",
                List.of())));

    var preview =
        json(
            as(TestJwt.admin(), post(url()), Json.obj("instruction", "Thêm số đơn hàng"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseSemanticVersion").value("1.0.0"))
                .andExpect(jsonPath("$.suggestedSemanticVersion").value("1.1.0")));
    var metrics = Json.objects(preview.get("metrics"));
    assertThat(metrics).extracting(m -> m.get("status")).containsExactly("CHANGED", "NEW");
    assertThat(Json.str(Json.map(metrics.get(0).get("current")), "expression"))
        .isEqualTo("SUM(orders.total)");
    assertThat(Json.objects(preview.get("dimensions")))
        .extracting(d -> d.get("status"))
        .containsExactly("UNCHANGED");
    assertThat(Json.str(Json.read(AI.lastAssistRequest.get()), "instruction"))
        .isEqualTo("Thêm số đơn hàng");

    // The active formula is still the old one.
    as(TestJwt.admin(), get(model), null)
        .andExpect(jsonPath("$.metrics[0].expression").value("SUM(orders.total)"));
  }

  @Test
  void aiFailuresAreControlledAndManualEditingStillWorks() throws Exception {
    AI.assistStatus.set(504);
    AI.assistBody.set("{\"error\":{\"code\":\"PROVIDER_TIMEOUT\"}}");
    as(TestJwt.admin(), post(url()), Json.obj())
        .andExpect(status().isGatewayTimeout())
        .andExpect(jsonPath("$.error.code").value("PROVIDER_TIMEOUT"));

    AI.assistStatus.set(502);
    AI.assistBody.set("{\"error\":{\"code\":\"INVALID_MODEL_RESPONSE\"}}");
    as(TestJwt.admin(), post(url()), Json.obj())
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.code").value("INVALID_MODEL_RESPONSE"));

    // A 200 that breaks the private contract is not trusted either.
    AI.assistStatus.set(200);
    AI.assistBody.set("{\"model\":\"m\",\"metrics\":\"oops\"}");
    as(TestJwt.admin(), post(url()), Json.obj())
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.code").value("INVALID_MODEL_RESPONSE"));

    // The manual semantic editor is unaffected.
    as(TestJwt.admin(), get("/api/v1/datasets/" + datasetId + "/semantic-model"), null)
        .andExpect(status().isOk());
  }

  @Test
  void requestIsValidatedAndAdminOnly() throws Exception {
    as(TestJwt.analyst(), post(url()), Json.obj()).andExpect(status().isForbidden());
    as(TestJwt.admin(), post(url()), Json.obj("sql", "DROP TABLE shop.orders"))
        .andExpect(status().isBadRequest());
    as(TestJwt.admin(), post(url()), Json.obj("instruction", "x".repeat(2001)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void noConfiguredModelIsReportedClearly() throws Exception {
    AI.providerStatus.set("{\"provider\":null}");
    as(TestJwt.admin(), post(url()), Json.obj())
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("MODEL_PROVIDER_UNAVAILABLE"));
  }
}
