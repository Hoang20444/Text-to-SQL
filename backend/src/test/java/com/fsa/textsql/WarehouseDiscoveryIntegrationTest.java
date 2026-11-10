package com.fsa.textsql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsa.textsql.security.TestJwt;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-2.1.1 on real engines: register -> test connection -> background discovery (schemas, tables,
 * columns, nullable, PK, FK, schemaVersion) -> dataset -> catalog, for PostgreSQL, MySQL and SQL
 * Server. The PostgreSQL container also hosts the metadata store.
 */
@SpringBootTest(
    properties = {
      "spring.config.import=",
      "nla.local-bootstrap.enabled=false",
      "nla.query.allowed-hosts="
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(TestJwt.DecoderConfig.class)
class WarehouseDiscoveryIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

  @Container
  static MSSQLServerContainer<?> mssql =
      new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-CU22-ubuntu-22.04")
          .acceptLicense();

  @DynamicPropertySource
  static void secrets(DynamicPropertyRegistry registry) {
    // Warehouse passwords are only ever referenced as env:NAME; tests provide the values.
    registry.add("TEST_PG_PASSWORD", pg::getPassword);
    registry.add("TEST_MYSQL_PASSWORD", mysql::getPassword);
    registry.add("TEST_MSSQL_PASSWORD", mssql::getPassword);
    registry.add("TEST_WRONG_PASSWORD", () -> "definitely-not-the-password");
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @BeforeAll
  static void createShopSchemas() throws Exception {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Statement s = c.createStatement()) {
      s.execute("CREATE SCHEMA shop");
      s.execute(
          "CREATE TABLE shop.customer (customer_id integer PRIMARY KEY, full_name varchar(100) NOT NULL, note text)");
      s.execute(
          "CREATE TABLE shop.orders (order_id bigint PRIMARY KEY, customer_id integer NOT NULL"
              + " REFERENCES shop.customer(customer_id), total numeric(10,2))");
      s.execute("CREATE TABLE shop.temp_extra (id integer PRIMARY KEY)");
    }
    try (Connection c =
            DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE shop");
      s.execute(
          "CREATE TABLE shop.customer (customer_id int PRIMARY KEY, full_name varchar(100) NOT NULL, note text) ENGINE=InnoDB");
      s.execute(
          "CREATE TABLE shop.orders (order_id bigint PRIMARY KEY, customer_id int NOT NULL, total decimal(10,2),"
              + " CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES shop.customer(customer_id)) ENGINE=InnoDB");
      s.execute("CREATE TABLE shop.temp_extra (id int PRIMARY KEY) ENGINE=InnoDB");
    }
    try (Connection c =
            DriverManager.getConnection(
                mssql.getJdbcUrl(), mssql.getUsername(), mssql.getPassword());
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE shopdb");
      s.execute("USE shopdb");
      s.execute("EXEC('CREATE SCHEMA shop')");
      s.execute(
          "CREATE TABLE shop.customer (customer_id int PRIMARY KEY, full_name nvarchar(100) NOT NULL, note nvarchar(max))");
      s.execute(
          "CREATE TABLE shop.orders (order_id bigint PRIMARY KEY, customer_id int NOT NULL"
              + " CONSTRAINT fk_orders_customer REFERENCES shop.customer(customer_id), total decimal(10,2))");
      s.execute("CREATE TABLE shop.temp_extra (id int PRIMARY KEY)");
    }
  }

  static Stream<Arguments> engines() {
    return Stream.of(
        Arguments.of(
            "POSTGRESQL",
            pg.getHost(),
            pg.getMappedPort(5432),
            pg.getDatabaseName(),
            pg.getUsername(),
            "env:TEST_PG_PASSWORD",
            "shop"),
        Arguments.of(
            "MYSQL",
            mysql.getHost(),
            mysql.getMappedPort(3306),
            "shop",
            "root",
            "env:TEST_MYSQL_PASSWORD",
            "shop"),
        Arguments.of(
            "SQL_SERVER",
            mssql.getHost(),
            mssql.getMappedPort(1433),
            "shopdb",
            mssql.getUsername(),
            "env:TEST_MSSQL_PASSWORD",
            "shop"));
  }

  private ResultActions admin(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
      throws Exception {
    return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.admin())));
  }

  private String register(
      String engine, String host, int port, String database, String user, String credential)
      throws Exception {
    String body =
        admin(
                post("/api/v1/warehouses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "name",
                                "Shop " + engine,
                                "engine",
                                engine,
                                "host",
                                host,
                                "port",
                                port,
                                "database",
                                database,
                                "username",
                                user,
                                "credentialReference",
                                credential))))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", startsWith("/api/v1/warehouses/wh-")))
            .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.credentialReference").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("TEST_", "env:");
    return json.readTree(body).get("warehouseId").asText();
  }

  private JsonNode discoverAndWait(String warehouseId) throws Exception {
    String started =
        admin(post("/api/v1/warehouses/" + warehouseId + "/discover"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.runId").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String runId = json.readTree(started).get("runId").asText();
    for (int i = 0; i < 120; i++) {
      JsonNode run =
          json.readTree(
              admin(get("/api/v1/warehouses/" + warehouseId + "/discoveries/" + runId))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      String state = run.get("status").asText();
      if (state.equals("SUCCESS") || state.equals("FAILED")) {
        return run;
      }
      Thread.sleep(500);
    }
    throw new AssertionError("discovery did not finish in time");
  }

  private static JsonNode table(JsonNode run, String schema, String name) {
    for (JsonNode t : run.at("/metadata/tables")) {
      if (t.get("schema").asText().equals(schema) && t.get("name").asText().equals(name)) {
        return t;
      }
    }
    throw new AssertionError("table " + schema + "." + name + " not discovered");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("engines")
  void registerTestDiscoverAndBrowseCatalog(
      String engine,
      String host,
      int port,
      String database,
      String user,
      String credential,
      String schema)
      throws Exception {
    String warehouseId = register(engine, host, port, database, user, credential);

    // AC1: connection test with latency; success makes the warehouse ACTIVE.
    admin(post("/api/v1/warehouses/" + warehouseId + "/test"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reachable").value(true))
        .andExpect(jsonPath("$.latencyMs").isNumber())
        .andExpect(jsonPath("$.error").doesNotExist());
    admin(get("/api/v1/warehouses/" + warehouseId))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.host").value(host))
        .andExpect(jsonPath("$.credentialReference").doesNotExist());

    // Catalog before any scan: explicit NOT_DISCOVERED, never an empty fake catalog.
    // (No dataset yet; dataset creation also refuses an unscanned warehouse.)
    admin(
            post("/api/v1/datasets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "warehouseId",
                            warehouseId,
                            "name",
                            "x",
                            "allowedTables",
                            List.of(Map.of("schema", schema, "name", "customer"))))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.details.reason").value("NOT_DISCOVERED"));

    // AC2: tables, columns, types, nullable, PK, FK and schemaVersion on every engine.
    JsonNode run = discoverAndWait(warehouseId);
    assertThat(run.get("status").asText()).isEqualTo("SUCCESS");
    String version = run.get("schemaVersion").asText();
    assertThat(version).startsWith("sha256:");
    JsonNode customer = table(run, schema, "customer");
    assertThat(customer.get("primaryKey").get(0).asText()).isEqualTo("customer_id");
    assertThat(customer.at("/columns/0/name").asText()).isEqualTo("customer_id");
    assertThat(customer.at("/columns/0/dataType").asText()).isEqualTo("INTEGER");
    assertThat(customer.at("/columns/1/nullable").asBoolean()).isFalse();
    assertThat(customer.at("/columns/2/nullable").asBoolean()).isTrue();
    JsonNode orders = table(run, schema, "orders");
    assertThat(orders.at("/columns/0/dataType").asText()).isEqualTo("BIGINT");
    assertThat(orders.at("/columns/2/dataType").asText()).isEqualTo("DECIMAL");
    assertThat(orders.at("/foreignKeys/0/from/column").asText()).isEqualTo("customer_id");
    assertThat(orders.at("/foreignKeys/0/to/table/name").asText()).isEqualTo("customer");
    assertThat(orders.at("/foreignKeys/0/to/table/schema").asText()).isEqualTo(schema);
    assertThat(run.toString()).doesNotContain("TEST_", "env:");

    // Same structure => same schemaVersion.
    assertThat(discoverAndWait(warehouseId).get("schemaVersion").asText()).isEqualTo(version);

    // Dataset with the scanned version, then the catalog (AC3).
    String created =
        admin(
                post("/api/v1/datasets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "warehouseId",
                                warehouseId,
                                "name",
                                "Shop",
                                "allowedTables",
                                List.of(
                                    Map.of("schema", schema, "name", "customer"),
                                    Map.of("schema", schema, "name", "orders"))))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.schemaVersion").value(version))
            .andExpect(jsonPath("$.semanticVersion").value("0.0.0"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String datasetId = json.readTree(created).get("datasetId").asText();

    admin(get("/api/v1/datasets/" + datasetId + "/catalog"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tableCount").value(2))
        .andExpect(jsonPath("$.relationshipCount").value(1))
        .andExpect(jsonPath("$.schemaVersion").value(version))
        .andExpect(jsonPath("$.tableCategories[0].name").value(schema));
    String tables =
        admin(get("/api/v1/datasets/" + datasetId + "/tables").param("search", "TOTAL"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalItems").value(1))
            .andExpect(jsonPath("$.items[0].name").value("orders"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String tableId = json.readTree(tables).at("/items/0/tableId").asText();
    admin(get("/api/v1/datasets/" + datasetId + "/tables/" + tableId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AVAILABLE"))
        .andExpect(jsonPath("$.columns.length()").value(3))
        .andExpect(jsonPath("$.foreignKeys[0].to.table.name").value("customer"));
    admin(get("/api/v1/datasets/" + datasetId + "/relationships"))
        .andExpect(jsonPath("$.items[0].source").value("FOREIGN_KEY"))
        .andExpect(jsonPath("$.items[0].from.table.name").value("orders"));

    // A table missing from the scan cannot be added to a dataset.
    admin(
            post("/api/v1/datasets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "warehouseId",
                            warehouseId,
                            "name",
                            "Bad",
                            "allowedTables",
                            List.of(Map.of("schema", schema, "name", "no_such_table"))))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SCHEMA_MISMATCH"));
  }

  @Test
  void droppedTableMakesDatasetInvalidAfterRescan() throws Exception {
    String warehouseId =
        register(
            "POSTGRESQL",
            pg.getHost(),
            pg.getMappedPort(5432),
            pg.getDatabaseName(),
            pg.getUsername(),
            "env:TEST_PG_PASSWORD");
    discoverAndWait(warehouseId);
    String created =
        admin(
                post("/api/v1/datasets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "warehouseId",
                                warehouseId,
                                "name",
                                "Extra",
                                "allowedTables",
                                List.of(Map.of("schema", "shop", "name", "temp_extra"))))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String datasetId = json.readTree(created).get("datasetId").asText();

    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Statement s = c.createStatement()) {
      s.execute("DROP TABLE shop.temp_extra");
    }
    JsonNode run = discoverAndWait(warehouseId);
    assertThat(run.get("status").asText()).isEqualTo("SUCCESS");

    admin(get("/api/v1/datasets").param("warehouseId", warehouseId))
        .andExpect(jsonPath("$.items[0].datasetId").value(datasetId))
        .andExpect(jsonPath("$.items[0].status").value("INVALID_SCHEMA"));
    admin(get("/api/v1/datasets/" + datasetId + "/tables"))
        .andExpect(jsonPath("$.items[0].status").value("MISSING"))
        .andExpect(jsonPath("$.items[0].queryable").value(false));
  }

  @Test
  void failedConnectionGivesReasonWithoutSecrets() throws Exception {
    String wrong =
        register(
            "MYSQL",
            mysql.getHost(),
            mysql.getMappedPort(3306),
            "shop",
            "root",
            "env:TEST_WRONG_PASSWORD");
    String body =
        admin(post("/api/v1/warehouses/" + wrong + "/test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reachable").value(false))
            .andExpect(jsonPath("$.error.code").value("WAREHOUSE_CONNECTION_ERROR"))
            .andExpect(jsonPath("$.error.details.reason").value("AUTHENTICATION_FAILED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("definitely-not-the-password", "root@", "TEST_WRONG");
    admin(get("/api/v1/warehouses/" + wrong)).andExpect(jsonPath("$.status").value("UNAVAILABLE"));

    String unset =
        register(
            "POSTGRESQL",
            pg.getHost(),
            pg.getMappedPort(5432),
            pg.getDatabaseName(),
            pg.getUsername(),
            "env:NOT_CONFIGURED_ANYWHERE");
    admin(post("/api/v1/warehouses/" + unset + "/test"))
        .andExpect(jsonPath("$.reachable").value(false))
        .andExpect(jsonPath("$.error.details.reason").value("CREDENTIAL_NOT_CONFIGURED"));

    // Discovery of an unreachable warehouse fails with the same sanitized reason.
    JsonNode run = discoverAndWait(wrong);
    assertThat(run.get("status").asText()).isEqualTo("FAILED");
    assertThat(run.at("/error/details/reason").asText()).isEqualTo("AUTHENTICATION_FAILED");
    assertThat(run.get("metadata").isNull()).isTrue();
  }

  @Test
  void onlyAdminsManageWarehousesAndInputIsValidated() throws Exception {
    Map<String, Object> valid =
        Map.of(
            "name",
            "X",
            "engine",
            "POSTGRESQL",
            "host",
            "127.0.0.1",
            "port",
            5432,
            "database",
            "db",
            "username",
            "u",
            "credentialReference",
            "env:SOME_SECRET");
    mvc.perform(
            post("/api/v1/warehouses")
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.analyst()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(valid)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/warehouses/wh-any/discover")
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.viewer())))
        .andExpect(status().isForbidden());

    Map<String, Object> plainPassword = new java.util.HashMap<>(valid);
    plainPassword.put("credentialReference", "SuperSecret123");
    admin(
            post("/api/v1/warehouses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(plainPassword)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.details.parameter").value("credentialReference"))
        .andExpect(jsonPath("$.error.message", not(containsString("SuperSecret123"))));

    Map<String, Object> badEngine = new java.util.HashMap<>(valid);
    badEngine.put("engine", "ORACLE");
    admin(
            post("/api/v1/warehouses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(badEngine)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.details.parameter").value("engine"));

    Map<String, Object> withPassword = new java.util.HashMap<>(valid);
    withPassword.put("password", "x");
    admin(
            post("/api/v1/warehouses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(withPassword)))
        .andExpect(status().isBadRequest());
  }
}
