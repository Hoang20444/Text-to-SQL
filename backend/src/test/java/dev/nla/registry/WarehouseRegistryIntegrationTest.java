package dev.nla.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.nla.common.Json;
import dev.nla.support.TestJwt;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-2.1.1 on real engines: register -> test connection -> discovery (schemas, tables, columns,
 * types, nullable, PK, FK) -> dataset -> Data Catalog, for PostgreSQL, MySQL and SQL Server.
 */
@SpringBootTest(
    properties = {
      "spring.config.import=",
      "nla.local-bootstrap=false",
      "nla.warehouse-tls=false",
      "OIDC_ISSUER_URI=" + TestJwt.ISSUER,
      "OIDC_AUDIENCE=" + TestJwt.AUDIENCE,
      "NLA_MYSQL_RSA_PUBLIC_KEY=",
      "TEST_WRONG_PASSWORD=definitely-not-the-password"
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(TestJwt.DecoderConfig.class)
class WarehouseRegistryIntegrationTest {
  @Container @ServiceConnection
  static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

  @Container
  static MSSQLServerContainer<?> mssql =
      new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-CU22-ubuntu-22.04")
          .acceptLicense();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("WAREHOUSE_ALLOWED_HOSTS", () -> String.join(",", pg.getHost(), "localhost"));
    registry.add("TEST_PG_PASSWORD", pg::getPassword);
    registry.add("TEST_MYSQL_PASSWORD", mysql::getPassword);
    registry.add("TEST_MSSQL_PASSWORD", mssql::getPassword);
  }

  @Autowired MockMvc mvc;

  @BeforeAll
  static void createShopSchemas() throws Exception {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Statement s = c.createStatement()) {
      s.execute("CREATE SCHEMA shop");
      s.execute(
          "CREATE TABLE shop.customer (customer_id integer PRIMARY KEY,"
              + " full_name varchar(100) NOT NULL, note text)");
      s.execute(
          "CREATE TABLE shop.orders (order_id bigint PRIMARY KEY, customer_id integer NOT NULL"
              + " REFERENCES shop.customer(customer_id), total numeric(10,2))");
    }
    try (Connection c =
            DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE shop");
      s.execute(
          "CREATE TABLE shop.customer (customer_id int PRIMARY KEY,"
              + " full_name varchar(100) NOT NULL, note text) ENGINE=InnoDB");
      s.execute(
          "CREATE TABLE shop.orders (order_id bigint PRIMARY KEY, customer_id int NOT NULL,"
              + " total decimal(10,2), CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id)"
              + " REFERENCES shop.customer(customer_id)) ENGINE=InnoDB");
    }
    try (Connection c =
            DriverManager.getConnection(
                mssql.getJdbcUrl(), mssql.getUsername(), mssql.getPassword());
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE shopdb");
      s.execute("USE shopdb");
      s.execute("EXEC('CREATE SCHEMA shop')");
      s.execute(
          "CREATE TABLE shop.customer (customer_id int PRIMARY KEY,"
              + " full_name nvarchar(100) NOT NULL, note nvarchar(max))");
      s.execute(
          "CREATE TABLE shop.orders (order_id bigint PRIMARY KEY, customer_id int NOT NULL"
              + " CONSTRAINT fk_orders_customer REFERENCES shop.customer(customer_id),"
              + " total decimal(10,2))");
    }
  }

  private static Map<String, Object> connection(String engine) {
    return switch (engine) {
      case "POSTGRESQL" ->
          Json.obj(
              "name",
              "Shop PG",
              "engine",
              engine,
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
              "env:TEST_PG_PASSWORD");
      case "MYSQL" ->
          Json.obj(
              "name", "Shop MySQL",
              "engine", engine,
              "host", mysql.getHost(),
              "port", mysql.getMappedPort(3306),
              "database", "shop",
              "defaultSchema", "shop",
              "username", "root",
              "credentialReference", "env:TEST_MYSQL_PASSWORD");
      default ->
          Json.obj(
              "name",
              "Shop SQL Server",
              "engine",
              engine,
              "host",
              mssql.getHost(),
              "port",
              mssql.getMappedPort(1433),
              "database",
              "shopdb",
              "defaultSchema",
              "shop",
              "username",
              mssql.getUsername(),
              "credentialReference",
              "env:TEST_MSSQL_PASSWORD");
    };
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

  @ParameterizedTest
  @ValueSource(strings = {"POSTGRESQL", "MYSQL", "SQL_SERVER"})
  void registerTestDiscoverAndBrowseCatalog(String engine) throws Exception {
    var created =
        json(
            as(TestJwt.admin(), post("/api/v1/warehouses"), connection(engine))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.engine").value(engine))
                .andExpect(jsonPath("$.status").value("INVALID")));
    assertThat(created).doesNotContainKey("credentialReference");
    String id = Json.str(created, "warehouseId");

    as(TestJwt.admin(), post("/api/v1/warehouses/" + id + "/test"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reachable").value(true))
        .andExpect(jsonPath("$.latencyMs").isNumber());
    as(TestJwt.admin(), get("/api/v1/warehouses/" + id), null)
        .andExpect(jsonPath("$.status").value("ACTIVE"));

    var run =
        json(
            as(TestJwt.admin(), post("/api/v1/warehouses/" + id + "/discover"), null)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(
                    jsonPath("$.schemaVersion")
                        .value(org.hamcrest.Matchers.matchesPattern("sha256:[0-9a-f]{16}"))));
    as(TestJwt.admin(), get("/api/v1/warehouses/" + id), null)
        .andExpect(jsonPath("$.schemaVersion").value(Json.str(run, "schemaVersion")))
        .andExpect(jsonPath("$.lastDiscoveredAt").exists());
    assertThat(Json.objects(Json.map(run.get("metadata")).get("schemas")))
        .extracting(s -> Json.str(s, "name").toLowerCase())
        .doesNotContain("information_schema", "sys", "pg_catalog", "mysql");
    var shop =
        Json.objects(Json.map(run.get("metadata")).get("schemas")).stream()
            .filter(s -> "shop".equals(s.get("name")))
            .findFirst()
            .orElseThrow();
    var orders =
        Json.objects(shop.get("tables")).stream()
            .filter(t -> "orders".equals(t.get("name")))
            .findFirst()
            .orElseThrow();
    assertThat(orders.get("primaryKey")).isEqualTo(List.of("order_id"));
    var fk = Json.objects(orders.get("foreignKeys")).getFirst();
    assertThat(Json.map(Json.map(fk.get("to")).get("table")).get("name")).isEqualTo("customer");
    var columns = Json.objects(orders.get("columns"));
    assertThat(columns).extracting(c -> c.get("name")).contains("order_id", "total");
    assertThat(columns)
        .filteredOn(c -> "total".equals(c.get("name")))
        .extracting(c -> c.get("dataType"))
        .containsExactly("DECIMAL");
    as(TestJwt.admin(), get("/api/v1/warehouses/" + id + "/discoveries/" + run.get("runId")), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"));

    var dataset =
        json(
            as(
                    TestJwt.admin(),
                    post("/api/v1/datasets"),
                    Json.obj(
                        "warehouseId",
                        id,
                        "name",
                        "Shop",
                        "allowedSchemas",
                        List.of("shop"),
                        "allowedTables",
                        List.of(
                            Json.obj("schema", "shop", "name", "customer"),
                            Json.obj("schema", "shop", "name", "orders"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.semanticVersion").value("0.0.0"))
                .andExpect(
                    jsonPath("$.schemaVersion")
                        .value(org.hamcrest.Matchers.matchesPattern("sha256:[0-9a-f]{16}"))));
    String ds = Json.str(dataset, "datasetId");

    as(TestJwt.admin(), get("/api/v1/datasets/" + ds + "/catalog"), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tableCount").value(2))
        .andExpect(jsonPath("$.relationshipCount").value(1))
        .andExpect(jsonPath("$.tableCategories[0].name").value("shop"));
    var tables =
        json(
            as(TestJwt.admin(), get("/api/v1/datasets/" + ds + "/tables"), null)
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[*].name").value(hasItems("customer", "orders"))));
    String tableId = Json.str(Json.objects(tables.get("items")).getFirst(), "tableId");
    as(TestJwt.admin(), get("/api/v1/datasets/" + ds + "/tables/" + tableId), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tableId").value(tableId));
    as(TestJwt.admin(), get("/api/v1/datasets/" + ds + "/relationships"), null)
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].to.column").value("customer_id"));
  }

  @Test
  void wrongPasswordIsReportedWithoutDriverDetails() throws Exception {
    var request = connection("POSTGRESQL");
    request.put("credentialReference", "env:TEST_WRONG_PASSWORD");
    String id =
        Json.str(json(as(TestJwt.admin(), post("/api/v1/warehouses"), request)), "warehouseId");
    String body =
        as(TestJwt.admin(), post("/api/v1/warehouses/" + id + "/test"), null)
            .andExpect(jsonPath("$.reachable").value(false))
            .andExpect(jsonPath("$.error.code").value("WAREHOUSE_CONNECTION_ERROR"))
            .andExpect(jsonPath("$.error.details.reason").value("AUTHENTICATION_FAILED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("definitely-not-the-password", "password authentication");
    as(TestJwt.admin(), post("/api/v1/warehouses/" + id + "/discover"), null)
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.error.code").value("WAREHOUSE_CONNECTION_ERROR"))
        .andExpect(jsonPath("$.error.details.reason").value("AUTHENTICATION_FAILED"));
    // A database name that does not exist is reported separately.
    var missingDb = connection("POSTGRESQL");
    missingDb.put("database", "no_such_database");
    String other =
        Json.str(json(as(TestJwt.admin(), post("/api/v1/warehouses"), missingDb)), "warehouseId");
    as(TestJwt.admin(), post("/api/v1/warehouses/" + other + "/test"), null)
        .andExpect(jsonPath("$.error.details.reason").value("DATABASE_NOT_FOUND"));
    as(TestJwt.admin(), get("/api/v1/warehouses/" + id), null)
        .andExpect(jsonPath("$.status").value("UNAVAILABLE"));
  }

  @Test
  void registrationIsValidatedAndAdminOnly() throws Exception {
    as(TestJwt.analyst(), post("/api/v1/warehouses"), connection("POSTGRESQL"))
        .andExpect(status().isForbidden());
    var plainSecret = connection("POSTGRESQL");
    plainSecret.put("credentialReference", "my-password");
    as(TestJwt.admin(), post("/api/v1/warehouses"), plainSecret).andExpect(status().isBadRequest());
    var unknownSecret = connection("POSTGRESQL");
    unknownSecret.put("credentialReference", "env:NOT_CONFIGURED_ANYWHERE");
    as(TestJwt.admin(), post("/api/v1/warehouses"), unknownSecret)
        .andExpect(status().isServiceUnavailable());
    var badHost = connection("POSTGRESQL");
    badHost.put("host", "10.0.0.99");
    as(TestJwt.admin(), post("/api/v1/warehouses"), badHost)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    var badEngine = connection("POSTGRESQL");
    badEngine.put("engine", "ORACLE");
    as(TestJwt.admin(), post("/api/v1/warehouses"), badEngine).andExpect(status().isBadRequest());
    var extra = connection("POSTGRESQL");
    extra.put("password", "x");
    as(TestJwt.admin(), post("/api/v1/warehouses"), extra).andExpect(status().isBadRequest());
  }

  @Test
  void datasetAllowlistMustMatchDiscoveredTablesAndCanBeDisabled() throws Exception {
    String id =
        Json.str(
            json(as(TestJwt.admin(), post("/api/v1/warehouses"), connection("POSTGRESQL"))),
            "warehouseId");
    as(
            TestJwt.admin(),
            post("/api/v1/datasets"),
            Json.obj(
                "warehouseId",
                id,
                "name",
                "Bad",
                "allowedSchemas",
                List.of("shop"),
                "allowedTables",
                List.of(Json.obj("schema", "shop", "name", "nope"))))
        .andExpect(status().isBadRequest());
    // Versions are set by the server; a client-supplied version is rejected.
    as(
            TestJwt.admin(),
            post("/api/v1/datasets"),
            Json.obj(
                "warehouseId",
                id,
                "name",
                "Versioned",
                "allowedSchemas",
                List.of("shop"),
                "allowedTables",
                List.of(Json.obj("schema", "shop", "name", "customer")),
                "schemaVersion",
                "client-made"))
        .andExpect(status().isBadRequest());
    String ds =
        Json.str(
            json(
                as(
                    TestJwt.admin(),
                    post("/api/v1/datasets"),
                    Json.obj(
                        "warehouseId",
                        id,
                        "name",
                        "Customers",
                        "allowedSchemas",
                        List.of("shop"),
                        "allowedTables",
                        List.of(Json.obj("schema", "shop", "name", "customer"))))),
            "datasetId");
    as(TestJwt.admin(), patch("/api/v1/datasets/" + ds), Json.obj("status", "DISABLED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISABLED"));
  }
}
