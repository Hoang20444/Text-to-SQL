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
 * T-3.1.2, AC4: the same semantic model is read, validated, saved and version-checked on
 * PostgreSQL, MySQL and SQL Server. Metric formulas are checked with each engine's identifier
 * quoting, so a formula that is valid on one engine is valid on all three.
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
class SemanticModelThreeEngineIntegrationTest {
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
              + " country varchar(60) NOT NULL)");
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
              + " country varchar(60) NOT NULL) ENGINE=InnoDB");
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
              + " country nvarchar(60) NOT NULL)");
      s.execute(
          "CREATE TABLE shop.orders (order_id bigint PRIMARY KEY, customer_id int NOT NULL"
              + " CONSTRAINT fk_orders_customer REFERENCES shop.customer(customer_id),"
              + " total decimal(10,2))");
    }
  }

  private static Map<String, Object> connection(String engine) {
    var common =
        switch (engine) {
          case "POSTGRESQL" ->
              Json.obj(
                  "host",
                  pg.getHost(),
                  "port",
                  pg.getMappedPort(5432),
                  "database",
                  pg.getDatabaseName(),
                  "username",
                  pg.getUsername(),
                  "credentialReference",
                  "env:TEST_PG_PASSWORD");
          case "MYSQL" ->
              Json.obj(
                  "host",
                  mysql.getHost(),
                  "port",
                  mysql.getMappedPort(3306),
                  "database",
                  "shop",
                  "username",
                  "root",
                  "credentialReference",
                  "env:TEST_MYSQL_PASSWORD");
          default ->
              Json.obj(
                  "host",
                  mssql.getHost(),
                  "port",
                  mssql.getMappedPort(1433),
                  "database",
                  "shopdb",
                  "username",
                  mssql.getUsername(),
                  "credentialReference",
                  "env:TEST_MSSQL_PASSWORD");
        };
    common.put("name", "Shop semantic " + engine);
    common.put("engine", engine);
    common.put("defaultSchema", "shop");
    return common;
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

  private String registerDataset(String engine) throws Exception {
    var warehouse =
        json(
            as(TestJwt.admin(), post("/api/v1/warehouses"), connection(engine))
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
                        "Shop " + engine,
                        "allowedSchemas",
                        List.of("shop"),
                        "allowedTables",
                        List.of(
                            Json.obj("schema", "shop", "name", "customer"),
                            Json.obj("schema", "shop", "name", "orders"))))
                .andExpect(status().isCreated()));
    return Json.str(dataset, "datasetId");
  }

  /** The same business definitions on every engine: revenue by customer country. */
  private Map<String, Object> model(String url, String version, String expression)
      throws Exception {
    var model = new LinkedHashMap<>(json(as(TestJwt.admin(), get(url), null)));
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
                List.of("quốc gia"))));
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

  @ParameterizedTest
  @ValueSource(strings = {"POSTGRESQL", "MYSQL", "SQL_SERVER"})
  void semanticModelBehavesTheSameOnEveryEngine(String engine) throws Exception {
    String url = "/api/v1/datasets/" + registerDataset(engine) + "/semantic-model";

    var initial =
        json(
            as(TestJwt.admin(), get(url), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.semanticVersion").value("0.0.0")));
    assertThat(Json.objects(initial.get("tables")))
        .extracting(t -> t.get("name"))
        .containsExactlyInAnyOrder("customer", "orders");
    assertThat(Json.objects(initial.get("relationships"))).hasSize(1);

    as(TestJwt.admin(), post(url + "/validate"), model(url, "1.0.0", "SUM(orders.total)"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid").value(true))
        .andExpect(jsonPath("$.diagnostics").isEmpty());

    as(TestJwt.admin(), put(url), model(url, "1.0.0", "SUM(orders.total)"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.semanticVersion").value("1.0.0"))
        .andExpect(jsonPath("$.metrics[0].expression").value("SUM(orders.total)"))
        .andExpect(jsonPath("$.relationships.length()").value(1));

    as(TestJwt.admin(), put(url), model(url, "1.0.0", "SUM(orders.total) / 2"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SEMANTIC_MODEL_INVALID"));

    as(TestJwt.admin(), put(url), model(url, "1.1.0", "SUM(orders.amount)"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.details.path").value("metrics[0].expression"));

    as(TestJwt.admin(), put(url), model(url, "1.1.0", "COUNT(DISTINCT orders.customer_id)"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.semanticVersion").value("1.1.0"));
  }

  /**
   * A formula written with another engine's identifier quoting fails on save, not at query time.
   */
  @ParameterizedTest
  @ValueSource(strings = {"POSTGRESQL", "MYSQL", "SQL_SERVER"})
  void formulaMustUseTheWarehouseDialect(String engine) throws Exception {
    String url = "/api/v1/datasets/" + registerDataset(engine) + "/semantic-model";
    String backticks = "SUM(`orders`.`total`)";
    String brackets = "SUM([orders].[total])";

    var mysqlStyle = as(TestJwt.admin(), post(url + "/validate"), model(url, "1.0.0", backticks));
    var sqlServerStyle =
        as(TestJwt.admin(), post(url + "/validate"), model(url, "1.0.0", brackets));

    switch (engine) {
      case "MYSQL" -> {
        mysqlStyle.andExpect(status().isOk());
        sqlServerStyle.andExpect(status().isConflict());
      }
      case "SQL_SERVER" -> {
        mysqlStyle.andExpect(status().isConflict());
        sqlServerStyle.andExpect(status().isOk());
      }
      default -> {
        mysqlStyle.andExpect(status().isConflict());
        sqlServerStyle.andExpect(status().isConflict());
      }
    }
  }
}
