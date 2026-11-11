package dev.nla.query;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.nla.common.Json;
import dev.nla.store.Store;
import dev.nla.support.FakeAi;
import dev.nla.support.TestJwt;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-1.3.2 on all three target engines: the same question runs through discovery, the AI candidate,
 * the read-only check and bounded execution on PostgreSQL, MySQL and SQL Server. The PostgreSQL
 * container also hosts the metadata store.
 */
@SpringBootTest(
    properties = {
      "spring.config.import=",
      "nla.local-bootstrap=false",
      "nla.warehouse-tls=false",
      "OIDC_ISSUER_URI=" + TestJwt.ISSUER,
      "OIDC_AUDIENCE=" + TestJwt.AUDIENCE,
      "PYTHON_AI_TOKEN=" + FakeAi.TOKEN,
      // A developer's .local/docker.env may hold the local Docker MySQL key; not this container's.
      "NLA_MYSQL_RSA_PUBLIC_KEY="
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(TestJwt.DecoderConfig.class)
class ThreeEngineQueryIntegrationTest {
  @Container @ServiceConnection
  static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

  @Container
  static MSSQLServerContainer<?> mssql =
      new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-CU22-ubuntu-22.04")
          .acceptLicense();

  static final FakeAi AI = new FakeAi();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("PYTHON_AI_URL", AI::url);
    registry.add("WAREHOUSE_ALLOWED_HOSTS", () -> String.join(",", pg.getHost(), "localhost"));
    // Warehouse passwords are only referenced as env:NAME; tests provide the values.
    registry.add("TEST_PG_PASSWORD", pg::getPassword);
    registry.add("TEST_MYSQL_PASSWORD", mysql::getPassword);
    registry.add("TEST_MSSQL_PASSWORD", mssql::getPassword);
  }

  @AfterAll
  static void stopAi() {
    AI.stop();
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  private static final String ROWS =
      " VALUES (1,'VN',100.00),(2,'VN',200.00),(3,'US',50.25),(4,'US',49.75),(5,'JP',10.00)";

  @BeforeAll
  static void createSalesSchemas() throws Exception {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Statement s = c.createStatement()) {
      s.execute("CREATE SCHEMA sales");
      s.execute(
          "CREATE TABLE sales.orders (order_id int PRIMARY KEY, country varchar(10) NOT NULL,"
              + " amount numeric(10,2) NOT NULL)");
      s.execute("INSERT INTO sales.orders" + ROWS);
    }
    try (Connection c =
            DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE sales");
      s.execute(
          "CREATE TABLE sales.orders (order_id int PRIMARY KEY, country varchar(10) NOT NULL,"
              + " amount decimal(10,2) NOT NULL) ENGINE=InnoDB");
      s.execute("INSERT INTO sales.orders" + ROWS);
    }
    try (Connection c =
            DriverManager.getConnection(
                mssql.getJdbcUrl(), mssql.getUsername(), mssql.getPassword());
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE salesdb");
      s.execute("USE salesdb");
      s.execute("EXEC('CREATE SCHEMA sales')");
      s.execute(
          "CREATE TABLE sales.orders (order_id int PRIMARY KEY, country nvarchar(10) NOT NULL,"
              + " amount decimal(10,2) NOT NULL)");
      s.execute("INSERT INTO sales.orders" + ROWS);
    }
  }

  // Each engine gets its own ids so the 15 s discovery cache never serves another engine.
  private void register(String engine) {
    jdbc.update("DELETE FROM query_history");
    jdbc.update("DELETE FROM dataset");
    jdbc.update("DELETE FROM warehouse");
    var store = new Store(jdbc);
    String host;
    int port;
    String database;
    String user;
    String secret;
    switch (engine) {
      case "POSTGRESQL" -> {
        host = pg.getHost();
        port = pg.getMappedPort(5432);
        database = pg.getDatabaseName();
        user = pg.getUsername();
        secret = "PG";
      }
      case "MYSQL" -> {
        host = mysql.getHost();
        port = mysql.getMappedPort(3306);
        database = "sales";
        user = "root";
        secret = "MYSQL";
      }
      default -> {
        host = mssql.getHost();
        port = mssql.getMappedPort(1433);
        database = "salesdb";
        user = mssql.getUsername();
        secret = "MSSQL";
      }
    }
    store.insertWarehouse(
        Json.obj(
            "warehouseId", "wh-sales-" + engine,
            "name", "Sales " + engine,
            "engine", engine,
            "host", host,
            "port", port,
            "database", database,
            "defaultSchema", "sales",
            "username", user,
            "status", "ACTIVE"),
        "env:TEST_" + secret + "_PASSWORD");
    store.insertDataset(
        Json.obj(
            "datasetId",
            "ds-sales-" + engine,
            "warehouseId",
            "wh-sales-" + engine,
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

  @ParameterizedTest
  @ValueSource(strings = {"POSTGRESQL", "MYSQL", "SQL_SERVER"})
  void sameQuestionRunsOnEachEngine(String engine) throws Exception {
    register(engine);
    AI.answer(
        "SELECT country, SUM(amount) AS total_amount FROM sales.orders"
            + " GROUP BY country ORDER BY country");
    mvc.perform(
            post("/api/v1/query")
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.admin()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    Json.write(
                        Json.obj(
                            "warehouseId",
                            "wh-sales-" + engine,
                            "datasetId",
                            "ds-sales-" + engine,
                            "question",
                            "Doanh thu theo quốc gia?",
                            "mode",
                            "DETERMINISTIC"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.engine").value(engine))
        .andExpect(jsonPath("$.sqlDialect").value(engine))
        .andExpect(jsonPath("$.result.rows[*].country", contains("JP", "US", "VN")))
        .andExpect(jsonPath("$.result.rows[2].total_amount").value("300.00"))
        .andExpect(jsonPath("$.result.columns[1].semanticRole").value("METRIC"));
    // Without ORDER BY the row cap takes the other form (TOP on SQL Server, LIMIT elsewhere).
    AI.answer("SELECT order_id, country FROM sales.orders");
    mvc.perform(
            post("/api/v1/query")
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.admin()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    Json.write(
                        Json.obj(
                            "warehouseId",
                            "wh-sales-" + engine,
                            "datasetId",
                            "ds-sales-" + engine,
                            "question",
                            "Danh sách đơn hàng?",
                            "mode",
                            "DETERMINISTIC"))))
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.result.returnedRowCount").value(5))
        .andExpect(jsonPath("$.result.truncated").value(false));
  }
}
