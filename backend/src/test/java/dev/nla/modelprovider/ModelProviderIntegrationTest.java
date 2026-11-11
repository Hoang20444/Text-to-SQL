package dev.nla.modelprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import dev.nla.common.Json;
import dev.nla.store.Store;
import dev.nla.support.FakeAi;
import dev.nla.support.TestJwt;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-2.3.1: provider profiles with AES-GCM encrypted API keys, admin-only management, connection
 * test against an OpenAI-compatible /models endpoint, and routing of the default profile into the
 * private AI request without exposing the key in responses or history.
 */
@SpringBootTest(
    properties = {
      "spring.config.import=",
      "nla.local-bootstrap=false",
      "nla.warehouse-tls=false",
      "OIDC_ISSUER_URI=" + TestJwt.ISSUER,
      "OIDC_AUDIENCE=" + TestJwt.AUDIENCE,
      "PYTHON_AI_TOKEN=" + FakeAi.TOKEN,
      "TEST_WH_READER_PASSWORD=reader-secret-123",
      // Test-only 32-byte master key (base64); real deployments provision their own.
      "NLA_MODEL_MASTER_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
      "NLA_MODEL_PROVIDER_HOSTS=127.0.0.1"
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(TestJwt.DecoderConfig.class)
class ModelProviderIntegrationTest {
  static final String KEY = "sk-test-provider-key-123456";

  @Container @ServiceConnection
  static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

  static final FakeAi AI = new FakeAi();
  static final HttpServer PROVIDER;

  static {
    try {
      PROVIDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    // OpenAI-compatible model list that requires the right bearer key.
    PROVIDER.createContext(
        "/v1/models",
        exchange -> {
          boolean authorized =
              ("Bearer " + KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] body =
              (authorized
                      ? "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-test\"}]}"
                      : "{\"error\":\"bad key " + KEY + "\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(authorized ? 200 : 401, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    PROVIDER.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("PYTHON_AI_URL", AI::url);
    registry.add("WAREHOUSE_ALLOWED_HOSTS", () -> db.getHost());
  }

  @AfterAll
  static void stop() {
    AI.stop();
    PROVIDER.stop(0);
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  private static String providerUrl() {
    return "http://127.0.0.1:" + PROVIDER.getAddress().getPort() + "/v1";
  }

  @BeforeEach
  void reset() {
    jdbc.update("UPDATE model_provider_default SET provider_id=NULL");
    jdbc.update("DELETE FROM model_provider");
  }

  private ResultActions as(String token, MockHttpServletRequestBuilder request, Object body)
      throws Exception {
    request.header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(token));
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(Json.write(body));
    return mvc.perform(request);
  }

  private static String body(ResultActions result) throws Exception {
    return result.andReturn().getResponse().getContentAsString();
  }

  private Map<String, Object> online(String model) {
    return Json.obj(
        "name",
        "Test online",
        "kind",
        "ONLINE",
        "baseUrl",
        providerUrl(),
        "model",
        model,
        "enabled",
        true,
        "apiKey",
        KEY);
  }

  private String create(String model) throws Exception {
    return Json.str(
        Json.read(
            body(
                as(TestJwt.admin(), post("/api/v1/settings/model-providers"), online(model))
                    .andExpect(status().isCreated()))),
        "providerId");
  }

  @Test
  void apiKeyIsEncryptedAtRestAndNeverReturned() throws Exception {
    String created =
        body(
            as(TestJwt.admin(), post("/api/v1/settings/model-providers"), online("gpt-test"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasApiKey").value(true))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.revision").value("1")));
    assertThat(created).doesNotContain(KEY).doesNotContain("apiKey\"");
    String stored =
        jdbc.queryForObject("SELECT encrypted_api_key FROM model_provider", String.class);
    assertThat(stored).startsWith("v1:").doesNotContain(KEY);
    String listed =
        body(
            as(TestJwt.analyst(), get("/api/v1/settings/model-providers"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.configurationAvailable").value(true)));
    assertThat(listed).doesNotContain(KEY, "encrypted", "v1:");
  }

  @Test
  void onlyAdminManagesProviders() throws Exception {
    as(TestJwt.analyst(), post("/api/v1/settings/model-providers"), online("gpt-test"))
        .andExpect(status().isForbidden());
    String id = create("gpt-test");
    as(TestJwt.viewer(), post("/api/v1/settings/model-providers/" + id + "/test"), null)
        .andExpect(status().isForbidden());
    as(TestJwt.analyst(), delete("/api/v1/settings/model-providers/" + id), null)
        .andExpect(status().isForbidden());
  }

  @Test
  void invalidDestinationsAndKeysAreRejected() throws Exception {
    var notAllowed = online("gpt-test");
    notAllowed.put("baseUrl", "https://evil.example.com/v1");
    as(TestJwt.admin(), post("/api/v1/settings/model-providers"), notAllowed)
        .andExpect(status().isBadRequest());
    var withUserInfo = online("gpt-test");
    withUserInfo.put("baseUrl", "http://user:pass@127.0.0.1:1/v1");
    as(TestJwt.admin(), post("/api/v1/settings/model-providers"), withUserInfo)
        .andExpect(status().isBadRequest());
    var noKey = online("gpt-test");
    noKey.remove("apiKey");
    as(TestJwt.admin(), post("/api/v1/settings/model-providers"), noKey)
        .andExpect(status().isBadRequest());
    var keyInName = online("gpt-test");
    keyInName.put("name", "copy " + KEY);
    as(TestJwt.admin(), post("/api/v1/settings/model-providers"), keyInName)
        .andExpect(status().isBadRequest());
  }

  @Test
  void connectionTestReportsReachabilityModelAndWrongKey() throws Exception {
    String ok = create("gpt-test");
    as(TestJwt.admin(), post("/api/v1/settings/model-providers/" + ok + "/test"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reachable").value(true))
        .andExpect(jsonPath("$.modelAvailable").value(true))
        .andExpect(jsonPath("$.error").isEmpty());
    String missing = create("gpt-missing");
    as(TestJwt.admin(), post("/api/v1/settings/model-providers/" + missing + "/test"), null)
        .andExpect(jsonPath("$.modelAvailable").value(false))
        .andExpect(jsonPath("$.error.details.reason").value("MODEL_NOT_FOUND"));
    var wrongKey = online("gpt-test");
    wrongKey.put("apiKey", "sk-wrong-key-000000");
    wrongKey.put("revision", "1");
    as(TestJwt.admin(), put("/api/v1/settings/model-providers/" + ok), wrongKey)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value("2"));
    String result =
        body(
            as(TestJwt.admin(), post("/api/v1/settings/model-providers/" + ok + "/test"), null)
                .andExpect(jsonPath("$.reachable").value(false))
                .andExpect(jsonPath("$.error.details.reason").value("AUTHENTICATION_FAILED")));
    assertThat(result).doesNotContain("sk-wrong-key", KEY, "bad key");
  }

  @Test
  void staleRevisionAndDeletingTheDefaultAreConflicts() throws Exception {
    String id = create("gpt-test");
    var update = online("gpt-test");
    update.remove("apiKey");
    update.put("revision", "7");
    as(TestJwt.admin(), put("/api/v1/settings/model-providers/" + id), update)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("MODEL_PROVIDER_CONFLICT"));
    as(TestJwt.admin(), put("/api/v1/settings/model-providers/default"), Json.obj("providerId", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.defaultProviderId").value(id));
    as(TestJwt.admin(), delete("/api/v1/settings/model-providers/" + id), null)
        .andExpect(status().isConflict());
    as(
            TestJwt.admin(),
            put("/api/v1/settings/model-providers/default"),
            Json.obj("providerId", null))
        .andExpect(status().isOk());
    as(TestJwt.admin(), delete("/api/v1/settings/model-providers/" + id), null)
        .andExpect(status().isNoContent());
  }

  /** A real warehouse inside the metadata container, readable by a SELECT-only account. */
  private void seedWarehouse() {
    jdbc.execute(
        """
        CREATE SCHEMA IF NOT EXISTS sales;
        CREATE TABLE IF NOT EXISTS sales.orders (
          order_id bigint PRIMARY KEY, country varchar(50) NOT NULL, amount numeric(10,2));
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
    store.insertWarehouse(
        Json.obj(
            "warehouseId", "wh-test",
            "name", "Test",
            "engine", "POSTGRESQL",
            "host", db.getHost(),
            "port", db.getMappedPort(5432),
            "database", db.getDatabaseName(),
            "defaultSchema", "sales",
            "username", "wh_reader",
            "status", "ACTIVE"),
        "env:TEST_WH_READER_PASSWORD");
    store.insertDataset(
        Json.obj(
            "datasetId", "ds-test",
            "warehouseId", "wh-test",
            "name", "Sales",
            "allowedSchemas", List.of("sales"),
            "allowedTables", List.of(Json.obj("schema", "sales", "name", "orders")),
            "semanticVersion", "1.0.0",
            "schemaVersion", "test-1",
            "status", "ACTIVE"),
        Json.read(FakeAi.SEMANTIC_MODEL));
    store.grantWarehouseAccess("wh-test", TestJwt.ANALYST_SUB);
  }

  private static Map<String, Object> question(String mode) {
    return Json.obj(
        "warehouseId", "wh-test",
        "datasetId", "ds-test",
        "question", "Doanh thu theo quốc gia?",
        "mode", mode);
  }

  @Test
  void defaultProviderIsRoutedToTheAiServiceWithoutLeakingTheKey() throws Exception {
    String id = create("gpt-test");
    as(TestJwt.admin(), put("/api/v1/settings/model-providers/default"), Json.obj("providerId", id))
        .andExpect(status().isOk());
    seedWarehouse();
    AI.status.set(422);
    AI.body.set("{\"error\":{\"code\":\"UNSUPPORTED_QUESTION\"}}");
    String response =
        body(
            as(TestJwt.analyst(), post("/api/v1/query"), question("AUTO"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider.providerId").value(id))
                .andExpect(jsonPath("$.provider.model").value("gpt-test")));
    assertThat(response).doesNotContain(KEY);
    var sent = Json.map(Json.read(AI.lastRequest.get()).get("modelConfig"));
    assertThat(sent.get("kind")).isEqualTo("ONLINE");
    assertThat(sent.get("model")).isEqualTo("gpt-test");
    assertThat(sent.get("apiKey")).isEqualTo(KEY);
    assertThat(sent.get("structuredOutput")).isEqualTo(false);
    String history =
        jdbc.queryForObject(
            "SELECT string_agg(document::text,'') FROM query_history", String.class);
    assertThat(history).doesNotContain(KEY);
  }

  @Test
  void apiModeUsesTheChosenProfileAndDeterministicSendsNone() throws Exception {
    String id = create("gpt-test");
    seedWarehouse();
    AI.answer("SELECT country, SUM(amount) AS total_amount FROM sales.orders GROUP BY country");
    var api = question("API");
    api.put("providerId", id);
    as(TestJwt.analyst(), post("/api/v1/query"), api)
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.provider.providerId").value(id));
    assertThat(Json.read(AI.lastRequest.get())).containsKey("modelConfig");
    as(TestJwt.analyst(), post("/api/v1/query"), question("DETERMINISTIC"))
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.provider").isEmpty());
    assertThat(Json.read(AI.lastRequest.get())).doesNotContainKey("modelConfig");
    var auto = question("AUTO");
    auto.put("providerId", id);
    as(TestJwt.analyst(), post("/api/v1/query"), auto).andExpect(status().isBadRequest());
  }
}
