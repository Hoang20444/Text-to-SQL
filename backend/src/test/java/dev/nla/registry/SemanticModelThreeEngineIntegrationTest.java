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
import org.springframework.jdbc.core.JdbcTemplate;
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
  @Autowired JdbcTemplate jdbc;

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
      insertSampleRows(s);
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
      insertSampleRows(s);
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
      insertSampleRows(s);
    }
  }

  /** Same rows on every engine: VN has two customers and a NULL total, US has one order. */
  private static void insertSampleRows(Statement s) throws Exception {
    s.execute(
        "INSERT INTO shop.customer (customer_id, country) VALUES (1, 'VN'), (2, 'US'), (3, 'VN')");
    s.execute(
        "INSERT INTO shop.orders (order_id, customer_id, total) VALUES"
            + " (10, 1, 100.50), (11, 1, 20.00), (12, 2, 5.25), (13, 3, NULL), (14, 3, 9.75)");
    // Lookup tables without a primary key (T-3.3.1): country_info repeats 'VN',
    // country_code has one row per country, so only the second can be a "one" side.
    s.execute("CREATE TABLE shop.country_info (country varchar(60) NOT NULL, region varchar(60))");
    s.execute(
        "INSERT INTO shop.country_info (country, region) VALUES"
            + " ('VN', 'Asia'), ('VN', 'Southeast Asia'), ('US', 'America')");
    s.execute("CREATE TABLE shop.country_code (country varchar(60) NOT NULL, iso varchar(3))");
    s.execute("INSERT INTO shop.country_code (country, iso) VALUES ('VN', 'VNM'), ('US', 'USA')");
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
    return registerDataset(engine, List.of("customer", "orders"));
  }

  private String registerDataset(String engine, List<String> tableNames) throws Exception {
    var warehouse =
        json(
            as(TestJwt.admin(), post("/api/v1/warehouses"), connection(engine))
                .andExpect(status().isCreated()));
    // A new warehouse is INVALID until a successful connection test makes it ACTIVE.
    as(TestJwt.admin(), post("/api/v1/warehouses/" + warehouse.get("warehouseId") + "/test"), null)
        .andExpect(jsonPath("$.reachable").value(true));
    // ANALYST only sees warehouses granted to it; ADMIN sees all.
    jdbc.update(
        "INSERT INTO resource_access(subject,warehouse_id) VALUES (?,?)",
        TestJwt.ANALYST_SUB,
        warehouse.get("warehouseId"));
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
                        tableNames.stream()
                            .map(name -> Json.obj("schema", "shop", "name", name))
                            .toList()))
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

  /** Saves the revenue model and returns the dataset's semantic-query URL. */
  private String semanticQueryUrl(String engine) throws Exception {
    String dataset = registerDataset(engine);
    String url = "/api/v1/datasets/" + dataset + "/semantic-model";
    as(TestJwt.admin(), put(url), model(url, "1.0.0", "SUM(orders.total)"))
        .andExpect(status().isOk());
    return "/api/v1/datasets/" + dataset + "/semantic-query";
  }

  /** AC3 + AC4: the same metric query compiles per dialect and returns the same rows. */
  @Test
  void semanticQueryReturnsTheSameRowsOnEveryEngine() throws Exception {
    var expected =
        List.of(
            Json.obj("country", "US", "revenue", "5.25"),
            Json.obj("country", "VN", "revenue", "130.25"));
    for (String engine : List.of("POSTGRESQL", "MYSQL", "SQL_SERVER")) {
      String url = semanticQueryUrl(engine);

      var query =
          json(
              as(
                      TestJwt.analyst(),
                      post(url),
                      Json.obj("metrics", List.of("revenue"), "dimensions", List.of("country")))
                  .andExpect(status().isCreated())
                  .andExpect(jsonPath("$.status").value("SUCCESS"))
                  .andExpect(jsonPath("$.executionMode").value("SEMANTIC"))
                  .andExpect(jsonPath("$.model").value("semantic-compiler"))
                  .andExpect(jsonPath("$.semanticVersion").value("1.0.0"))
                  .andExpect(jsonPath("$.selectedMetrics[0]").value("revenue")));
      var rows = Json.objects(Json.map(query.get("result")).get("rows"));
      assertThat(rows).as(engine).isEqualTo(expected);
      assertThat(Json.str(query, "generatedSql")).as(engine).contains("GROUP BY");

      as(TestJwt.analyst(), get("/api/v1/query/" + query.get("queryId")), null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.executionMode").value("SEMANTIC"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"POSTGRESQL", "MYSQL", "SQL_SERVER"})
  void semanticQueryFiltersAndRejectsBadSelections(String engine) throws Exception {
    String url = semanticQueryUrl(engine);

    as(
            TestJwt.analyst(),
            post(url),
            Json.obj(
                "metrics",
                List.of("revenue"),
                "dimensions",
                List.of("country"),
                "filters",
                List.of(Json.obj("dimension", "country", "operator", "EQ", "value", "VN"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.result.rows.length()").value(1))
        .andExpect(jsonPath("$.result.rows[0].revenue").value("130.25"));

    // A quote inside the value stays data: no row matches, nothing is injected.
    as(
            TestJwt.analyst(),
            post(url),
            Json.obj(
                "metrics",
                List.of("revenue"),
                "dimensions",
                List.of("country"),
                "filters",
                List.of(
                    Json.obj("dimension", "country", "operator", "EQ", "value", "VN' OR '1'='1"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.result.rows.length()").value(0));

    as(TestJwt.analyst(), post(url), Json.obj("metrics", List.of("profit")))
        .andExpect(status().isBadRequest());
    as(TestJwt.analyst(), post(url), Json.obj("metrics", List.of()))
        .andExpect(status().isBadRequest());
    as(TestJwt.analyst(), post(url), Json.obj("metrics", List.of("revenue"), "sql", "SELECT 1"))
        .andExpect(status().isBadRequest());
    as(TestJwt.viewer(), post(url), Json.obj("metrics", List.of("revenue")))
        .andExpect(status().isForbidden());
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

  private static Map<String, Object> endpoint(String table, String column) {
    return Json.obj("table", Json.obj("schema", "shop", "name", table), "column", column);
  }

  /**
   * Revenue model on a dataset that also has the lookup tables: adds one dimension on lookupTable
   * and the declared relationship (if any) between customer.country and that table.
   */
  private Map<String, Object> lookupModel(
      String url, String lookupTable, String lookupColumn, Map<String, Object> relationship)
      throws Exception {
    var model = model(url, "1.0.0", "SUM(orders.total)");
    var dimensions = new java.util.ArrayList<>(Json.objects(model.get("dimensions")));
    dimensions.add(
        Json.obj(
            "name",
            lookupColumn,
            "table",
            Json.obj("schema", "shop", "name", lookupTable),
            "column",
            lookupColumn));
    model.put("dimensions", dimensions);
    var metric = new LinkedHashMap<>(Json.objects(model.get("metrics")).getFirst());
    metric.put("compatibleDimensions", List.of("country", lookupColumn));
    model.put("metrics", List.of(metric));
    model.put("relationships", relationship == null ? List.of() : List.of(relationship));
    return model;
  }

  private static final List<String> LOOKUP_TABLES =
      List.of("customer", "orders", "country_info", "country_code");

  /** T-3.3.1 AC1: a declared "one" side with duplicated values is rejected on every engine. */
  @ParameterizedTest
  @ValueSource(strings = {"POSTGRESQL", "MYSQL", "SQL_SERVER"})
  void duplicatedOneSideIsRejected(String engine) throws Exception {
    String url = "/api/v1/datasets/" + registerDataset(engine, LOOKUP_TABLES) + "/semantic-model";

    var manyToOne =
        lookupModel(
            url,
            "country_info",
            "region",
            Json.obj(
                "from",
                endpoint("customer", "country"),
                "to",
                endpoint("country_info", "country"),
                "cardinality",
                "MANY_TO_ONE"));
    as(TestJwt.admin(), put(url), manyToOne)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SEMANTIC_MODEL_INVALID"))
        .andExpect(jsonPath("$.error.details.path").value("relationships[0].to.column"));
    as(TestJwt.admin(), post(url + "/validate"), manyToOne)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.details.path").value("relationships[0].to.column"));

    // The same mistake written from the other direction is caught on the "from" side.
    var oneToMany =
        lookupModel(
            url,
            "country_info",
            "region",
            Json.obj(
                "from",
                endpoint("country_info", "country"),
                "to",
                endpoint("customer", "country"),
                "cardinality",
                "ONE_TO_MANY"));
    as(TestJwt.admin(), put(url), oneToMany)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.details.path").value("relationships[0].from.column"));

    // Nothing was saved.
    as(TestJwt.admin(), get(url), null).andExpect(jsonPath("$.semanticVersion").value("0.0.0"));
  }

  /** A unique column without a key is proven by the scan, saved, and joined at query time. */
  @Test
  void uniqueColumnWithoutKeyIsProvenAndGivesTheSameRowsOnEveryEngine() throws Exception {
    var expected =
        List.of(
            Json.obj("iso", "USA", "revenue", "5.25"), Json.obj("iso", "VNM", "revenue", "130.25"));
    for (String engine : List.of("POSTGRESQL", "MYSQL", "SQL_SERVER")) {
      String dataset = registerDataset(engine, LOOKUP_TABLES);
      String url = "/api/v1/datasets/" + dataset + "/semantic-model";
      var model =
          lookupModel(
              url,
              "country_code",
              "iso",
              Json.obj(
                  "from",
                  endpoint("customer", "country"),
                  "to",
                  endpoint("country_code", "country"),
                  "cardinality",
                  "MANY_TO_ONE"));
      as(TestJwt.admin(), put(url), model).andExpect(status().isOk());

      var query =
          json(
              as(
                      TestJwt.analyst(),
                      post("/api/v1/datasets/" + dataset + "/semantic-query"),
                      Json.obj("metrics", List.of("revenue"), "dimensions", List.of("iso")))
                  .andExpect(status().isCreated())
                  .andExpect(jsonPath("$.status").value("SUCCESS")));
      assertThat(Json.objects(Json.map(query.get("result")).get("rows")))
          .as(engine)
          .isEqualTo(expected);
    }
  }

  /** A compatible dimension with no safe join path is rejected when saving, not at query time. */
  @ParameterizedTest
  @ValueSource(strings = {"POSTGRESQL", "MYSQL", "SQL_SERVER"})
  void compatibleDimensionWithoutJoinPathIsRejected(String engine) throws Exception {
    String url = "/api/v1/datasets/" + registerDataset(engine, LOOKUP_TABLES) + "/semantic-model";

    as(TestJwt.admin(), put(url), lookupModel(url, "country_code", "iso", null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SEMANTIC_MODEL_INVALID"))
        .andExpect(jsonPath("$.error.details.path").value("metrics[0].compatibleDimensions"));
  }
}
