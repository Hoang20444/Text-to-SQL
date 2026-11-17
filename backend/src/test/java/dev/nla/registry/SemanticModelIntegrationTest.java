package dev.nla.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.nla.common.Json;
import dev.nla.support.TestJwt;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * T-3.1.2 on a real PostgreSQL warehouse: read the semantic model, save a new version, reject a
 * changed definition that keeps the old version, and validate references against live tables.
 */
@SpringBootTest(
    properties = {
      "spring.config.import=",
      "nla.local-bootstrap=false",
      "nla.warehouse-tls=false",
      "OIDC_ISSUER_URI=" + TestJwt.ISSUER,
      "OIDC_AUDIENCE=" + TestJwt.AUDIENCE,
      "NLA_MYSQL_RSA_PUBLIC_KEY="
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(TestJwt.DecoderConfig.class)
class SemanticModelIntegrationTest {
  @Container @ServiceConnection
  static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("WAREHOUSE_ALLOWED_HOSTS", () -> String.join(",", pg.getHost(), "localhost"));
    registry.add("TEST_PG_PASSWORD", pg::getPassword);
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
              + " REFERENCES shop.customer(customer_id), total numeric(10,2))");
    }
  }

  @BeforeEach
  void registerDataset() throws Exception {
    var warehouse =
        json(
            as(
                    TestJwt.admin(),
                    post("/api/v1/warehouses"),
                    Json.obj(
                        "name",
                        "Shop semantic " + System.nanoTime(),
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
    return "/api/v1/datasets/" + datasetId + "/semantic-model";
  }

  /** The GET document with one revenue metric and one country dimension. */
  private Map<String, Object> modelWithRevenue(String version, String expression) throws Exception {
    var model = new LinkedHashMap<>(json(as(TestJwt.admin(), get(url()), null)));
    model.put("semanticVersion", version);
    model.put(
        "dimensions",
        List.of(
            Json.obj(
                "name",
                "country",
                "description",
                "Quốc gia của khách hàng",
                "table",
                Json.obj("schema", "shop", "name", "customer"),
                "column",
                "country",
                "dataType",
                "VARCHAR",
                "synonyms",
                List.of("quốc gia", "nước"))));
    model.put(
        "metrics",
        List.of(
            Json.obj(
                "name",
                "revenue",
                "description",
                "Tổng doanh thu",
                "expression",
                expression,
                "unit",
                "USD",
                "tableRefs",
                List.of(Json.obj("schema", "shop", "name", "orders")),
                "compatibleDimensions",
                List.of("country"),
                "synonyms",
                List.of("doanh thu"))));
    return model;
  }

  @Test
  void getReturnsLiveTablesAndInitialVersion() throws Exception {
    var model =
        json(
            as(TestJwt.admin(), get(url()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasetId").value(datasetId))
                .andExpect(jsonPath("$.semanticVersion").value("0.0.0"))
                .andExpect(jsonPath("$.metrics").isEmpty()));
    assertThat(Json.objects(model.get("tables")))
        .extracting(t -> t.get("name"))
        .containsExactlyInAnyOrder("customer", "orders");
    // The discovered foreign key orders.customer_id -> customer.customer_id is part of the context.
    assertThat(Json.objects(model.get("relationships"))).hasSize(1);
  }

  @Test
  void savingNewDefinitionsWithANewVersionIsPersisted() throws Exception {
    as(TestJwt.admin(), put(url()), modelWithRevenue("1.0.0", "SUM(total)"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.semanticVersion").value("1.0.0"))
        .andExpect(jsonPath("$.metrics[0].name").value("revenue"));

    as(TestJwt.admin(), get("/api/v1/datasets/" + datasetId), null)
        .andExpect(jsonPath("$.semanticVersion").value("1.0.0"));
    as(TestJwt.admin(), get("/api/v1/datasets/" + datasetId + "/metrics"), null)
        .andExpect(jsonPath("$.totalItems").value(1));
    // The discovered foreign key sent back by the client is not stored as a declaration.
    as(TestJwt.admin(), get("/api/v1/datasets/" + datasetId + "/relationships"), null)
        .andExpect(jsonPath("$.totalItems").value(1));
  }

  @Test
  void changingAFormulaWithoutANewVersionIsRejected() throws Exception {
    as(TestJwt.admin(), put(url()), modelWithRevenue("1.0.0", "SUM(total)"))
        .andExpect(status().isOk());

    as(TestJwt.admin(), put(url()), modelWithRevenue("1.0.0", "SUM(total) * 2"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SEMANTIC_MODEL_INVALID"));
    as(TestJwt.admin(), put(url()), modelWithRevenue("1.1.0", "SUM(total) * 2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.semanticVersion").value("1.1.0"));
  }

  @Test
  void firstDefinitionsCannotReuseTheInitialVersion() throws Exception {
    as(TestJwt.admin(), put(url()), modelWithRevenue("0.0.0", "SUM(total)"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SEMANTIC_MODEL_INVALID"));
  }

  @Test
  void unknownColumnIsRejected() throws Exception {
    var model = modelWithRevenue("1.0.0", "SUM(total)");
    var dimension = new LinkedHashMap<>(Json.objects(model.get("dimensions")).getFirst());
    dimension.put("column", "city");
    model.put("dimensions", List.of(dimension));

    as(TestJwt.admin(), put(url()), model)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SEMANTIC_MODEL_INVALID"))
        .andExpect(jsonPath("$.error.details.path").value("dimensions[0].column"));
  }

  @Test
  void duplicateNamesAreRejected() throws Exception {
    var model = modelWithRevenue("1.0.0", "SUM(total)");
    var copy = new LinkedHashMap<>(Json.objects(model.get("metrics")).getFirst());
    copy.put("name", "Revenue");
    model.put("metrics", List.of(Json.objects(model.get("metrics")).getFirst(), copy));

    as(TestJwt.admin(), put(url()), model)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SEMANTIC_MODEL_INVALID"))
        .andExpect(jsonPath("$.error.details.path").value("metrics[1].name"));
  }

  @Test
  void invalidRequestsAreRejected() throws Exception {
    var badVersion = modelWithRevenue("v1", "SUM(total)");
    as(TestJwt.admin(), put(url()), badVersion).andExpect(status().isBadRequest());

    var badZone = modelWithRevenue("1.0.0", "SUM(total)");
    badZone.put("timezone", "Mars/Olympus");
    as(TestJwt.admin(), put(url()), badZone).andExpect(status().isBadRequest());

    var unknownField = modelWithRevenue("1.0.0", "SUM(total)");
    unknownField.put("sql", "DROP TABLE shop.orders");
    as(TestJwt.admin(), put(url()), unknownField).andExpect(status().isBadRequest());

    var staleSchema = modelWithRevenue("1.0.0", "SUM(total)");
    staleSchema.put("schemaVersion", "sha256:0000000000000000");
    as(TestJwt.admin(), put(url()), staleSchema)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SCHEMA_MISMATCH"));
  }

  @Test
  void onlyAdminCanSave() throws Exception {
    as(TestJwt.analyst(), put(url()), modelWithRevenue("1.0.0", "SUM(total)"))
        .andExpect(status().isForbidden());
  }
}
