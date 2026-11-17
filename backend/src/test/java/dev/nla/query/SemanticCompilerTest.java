package dev.nla.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nla.common.ApiException;
import dev.nla.common.Json;
import dev.nla.warehouse.dialect.MySqlDialect;
import dev.nla.warehouse.dialect.PostgreSqlDialect;
import dev.nla.warehouse.dialect.SqlServerDialect;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** T-3.1.2: structured semantic selections compile to safe SQL on every dialect. */
class SemanticCompilerTest {
  private static Map<String, Object> table(String name) {
    return Json.obj("schema", "shop", "name", name);
  }

  /** orders (many) -> customer (one); line_item (many) -> orders (one). */
  private static Map<String, Object> context() {
    return Json.obj(
        "metrics",
        List.of(
            Json.obj(
                "name",
                "revenue",
                "expression",
                "SUM(orders.total)",
                "tableRefs",
                List.of(table("orders")),
                "compatibleDimensions",
                List.of("country", "status")),
            Json.obj(
                "name",
                "order_count",
                "expression",
                "COUNT(*)",
                "tableRefs",
                List.of(table("orders")),
                "compatibleDimensions",
                List.of("country")),
            Json.obj(
                "name",
                "items_sold",
                "expression",
                "SUM(line_item.quantity)",
                "tableRefs",
                List.of(table("line_item")),
                "compatibleDimensions",
                List.of("country"))),
        "dimensions",
        List.of(
            Json.obj("name", "country", "table", table("customer"), "column", "country"),
            Json.obj("name", "status", "table", table("orders"), "column", "status"),
            Json.obj("name", "product", "table", table("line_item"), "column", "product")),
        "relationships",
        List.of(
            Json.obj(
                "from",
                Json.obj("table", table("orders"), "column", "customer_id"),
                "to",
                Json.obj("table", table("customer"), "column", "customer_id"),
                "cardinality",
                "MANY_TO_ONE"),
            Json.obj(
                "from",
                Json.obj("table", table("orders"), "column", "order_id"),
                "to",
                Json.obj("table", table("line_item"), "column", "order_id"),
                "cardinality",
                "ONE_TO_MANY")));
  }

  @Test
  void revenueByCountryJoinsCustomerOnPostgreSql() {
    var compiled =
        new SemanticCompiler("POSTGRESQL", new PostgreSqlDialect()::quoteIdentifier)
            .compile(context(), List.of("revenue"), List.of("country"));

    assertThat(compiled.sql())
        .isEqualTo(
            "SELECT \"customer\".\"country\" AS \"country\", (SUM(orders.total)) AS \"revenue\""
                + " FROM \"shop\".\"orders\" \"orders\""
                + " JOIN \"shop\".\"customer\" \"customer\""
                + " ON \"orders\".\"customer_id\" = \"customer\".\"customer_id\""
                + " GROUP BY \"customer\".\"country\" ORDER BY \"customer\".\"country\"");
    assertThat(compiled.metrics()).containsExactly("revenue");
    assertThat(compiled.dimensions()).containsExactly("country");
  }

  @Test
  void quotingFollowsMySqlAndSqlServer() {
    var mysql =
        new SemanticCompiler("MYSQL", new MySqlDialect()::quoteIdentifier)
            .compile(context(), List.of("revenue"), List.of("country"));
    var sqlServer =
        new SemanticCompiler("SQL_SERVER", new SqlServerDialect()::quoteIdentifier)
            .compile(context(), List.of("revenue"), List.of("country"));

    assertThat(mysql.sql()).contains("FROM `shop`.`orders` `orders` JOIN `shop`.`customer`");
    assertThat(sqlServer.sql()).contains("FROM [shop].[orders] [orders] JOIN [shop].[customer]");
  }

  @Test
  void dimensionOnTheBaseTableNeedsNoJoin() {
    var compiled =
        new SemanticCompiler("POSTGRESQL", new PostgreSqlDialect()::quoteIdentifier)
            .compile(context(), List.of("revenue"), List.of("status"));

    assertThat(compiled.sql()).doesNotContain("JOIN").contains("GROUP BY \"orders\".\"status\"");
  }

  @Test
  void metricsWithoutDimensionsHaveNoGroupBy() {
    var compiled =
        new SemanticCompiler("POSTGRESQL", new PostgreSqlDialect()::quoteIdentifier)
            .compile(context(), List.of("revenue", "order_count"), List.of());

    assertThat(compiled.sql())
        .isEqualTo(
            "SELECT (SUM(orders.total)) AS \"revenue\", (COUNT(*)) AS \"order_count\""
                + " FROM \"shop\".\"orders\" \"orders\"");
  }

  @Test
  void joiningTowardsTheManySideIsRejected() {
    // revenue lives on orders; product lives on line_item (one order has many items).
    var compiler = new SemanticCompiler("POSTGRESQL", new PostgreSqlDialect()::quoteIdentifier);
    var withProduct = context();
    var metrics = Json.objects(withProduct.get("metrics"));
    var revenue = new java.util.LinkedHashMap<>(metrics.getFirst());
    revenue.put("compatibleDimensions", List.of("product"));
    withProduct.put("metrics", List.of(revenue));

    assertThatThrownBy(() -> compiler.compile(withProduct, List.of("revenue"), List.of("product")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("nhân bản số liệu");
  }

  @Test
  void manySideMetricCanUseOneSideDimensionThroughTwoJoins() {
    var compiled =
        new SemanticCompiler("POSTGRESQL", new PostgreSqlDialect()::quoteIdentifier)
            .compile(context(), List.of("items_sold"), List.of("country"));

    assertThat(compiled.sql())
        .contains("FROM \"shop\".\"line_item\" \"line_item\"")
        .contains("JOIN \"shop\".\"orders\" \"orders\"")
        .contains("JOIN \"shop\".\"customer\" \"customer\"");
  }

  @Test
  void incompatibleOrUnknownSelectionsAreRejected() {
    var compiler = new SemanticCompiler("POSTGRESQL", new PostgreSqlDialect()::quoteIdentifier);

    assertThatThrownBy(() -> compiler.compile(context(), List.of("order_count"), List.of("status")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("không tương thích");
    assertThatThrownBy(() -> compiler.compile(context(), List.of("profit"), List.of()))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("không có trong mô hình");
    assertThatThrownBy(
            () -> compiler.compile(context(), List.of("revenue", "items_sold"), List.of()))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("bảng gốc khác nhau");
  }

  @Test
  void filtersBecomeEscapedLiterals() {
    var filters =
        List.<Map<String, Object>>of(
            Json.obj("dimension", "country", "operator", "EQ", "value", "Viet'Nam"),
            Json.obj("dimension", "status", "operator", "NE", "value", "x' OR '1'='1"));

    var postgres =
        new SemanticCompiler("POSTGRESQL", new PostgreSqlDialect()::quoteIdentifier)
            .compile(context(), List.of("revenue"), List.of("country"), filters);
    var sqlServer =
        new SemanticCompiler("SQL_SERVER", new SqlServerDialect()::quoteIdentifier)
            .compile(context(), List.of("revenue"), List.of("country"), filters);

    assertThat(postgres.sql())
        .contains(
            " WHERE \"customer\".\"country\" = 'Viet''Nam'"
                + " AND \"orders\".\"status\" <> 'x'' OR ''1''=''1'"
                + " GROUP BY");
    assertThat(sqlServer.sql()).contains("[customer].[country] = N'Viet''Nam'");
  }

  @Test
  void mysqlEscapesBackslashAndContainsEscapesWildcards() {
    var filters =
        List.<Map<String, Object>>of(
            Json.obj("dimension", "country", "operator", "CONTAINS", "value", "50%_off\\"),
            Json.obj("dimension", "status", "operator", "IS_NOT_NULL"));

    var mysql =
        new SemanticCompiler("MYSQL", new MySqlDialect()::quoteIdentifier)
            .compile(context(), List.of("revenue"), List.of(), filters);

    assertThat(mysql.sql())
        .contains("`customer`.`country` LIKE '%50!%!_off\\\\%' ESCAPE '!'")
        .contains("`orders`.`status` IS NOT NULL")
        // Filtering on a dimension still joins its table, even when it is not selected.
        .contains("JOIN `shop`.`customer` `customer`")
        .doesNotContain("GROUP BY");
  }

  @Test
  void numbersAndBooleansAreFormattedPerDialect() {
    var filters =
        List.<Map<String, Object>>of(
            Json.obj("dimension", "status", "operator", "GTE", "value", 2.5),
            Json.obj("dimension", "status", "operator", "LT", "value", true));

    var postgres =
        new SemanticCompiler("POSTGRESQL", new PostgreSqlDialect()::quoteIdentifier)
            .compile(context(), List.of("revenue"), List.of(), filters);
    var sqlServer =
        new SemanticCompiler("SQL_SERVER", new SqlServerDialect()::quoteIdentifier)
            .compile(context(), List.of("revenue"), List.of(), filters);

    assertThat(postgres.sql()).contains(">= 2.5").contains("< TRUE");
    assertThat(sqlServer.sql()).contains(">= 2.5").contains("< 1");
  }

  @Test
  void filterOnIncompatibleDimensionIsRejected() {
    var compiler = new SemanticCompiler("POSTGRESQL", new PostgreSqlDialect()::quoteIdentifier);
    var filters =
        List.<Map<String, Object>>of(
            Json.obj("dimension", "status", "operator", "EQ", "value", "PAID"));

    assertThatThrownBy(
            () -> compiler.compile(context(), List.of("order_count"), List.of(), filters))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("không tương thích");
  }
}
