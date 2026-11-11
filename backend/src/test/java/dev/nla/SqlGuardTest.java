package dev.nla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nla.warehouse.MySqlDialect;
import dev.nla.warehouse.PostgreSqlDialect;
import dev.nla.warehouse.SqlServerDialect;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T-2.2.1: AST guard, table/column allowlist, dialect checks and row limiting. */
class SqlGuardTest {
  private final SqlGuard guard = new SqlGuard();
  private final List<Map<String, Object>> tables =
      List.of(
          Json.obj(
              "schema",
              "public",
              "name",
              "invoice",
              "columns",
              List.of(
                  Json.obj("name", "invoice_id"),
                  Json.obj("name", "customer_id"),
                  Json.obj("name", "total"),
                  Json.obj("name", "billing_country"))),
          Json.obj(
              "schema",
              "public",
              "name",
              "customer",
              "columns",
              List.of(Json.obj("name", "customer_id"), Json.obj("name", "country"))));

  private SqlGuard.Validated pg(String sql) {
    return guard.validate(sql, "POSTGRESQL", tables);
  }

  @Test
  void aggregateReturnsSourceTables() {
    var result =
        pg(
            "SELECT billing_country, SUM(total) AS sales FROM public.invoice"
                + " GROUP BY billing_country ORDER BY sales DESC");
    assertThat(result.sourceTables())
        .containsExactly(Json.obj("schema", "public", "name", "invoice"));
    assertThat(result.sql()).doesNotEndWith(";");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT i.billing_country, SUM(i.total) AS sales FROM public.invoice i"
            + " GROUP BY i.billing_country",
        "WITH s AS (SELECT billing_country, total FROM public.invoice)"
            + " SELECT s.billing_country, SUM(s.total) AS sales FROM s GROUP BY s.billing_country",
        "SELECT c.country, COUNT(*) AS n FROM public.invoice i"
            + " JOIN public.customer c ON c.customer_id = i.customer_id GROUP BY c.country",
        "SELECT billing_country FROM public.invoice WHERE billing_country = 'Drop; delete --'",
        "SELECT total FROM invoice;"
      })
  void safeReadsAreAccepted(String sql) {
    assertThat(pg(sql).sourceTables()).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "DELETE FROM public.invoice",
        "UPDATE public.invoice SET total = 0",
        "INSERT INTO public.invoice (invoice_id) VALUES (1)",
        "DROP TABLE public.invoice",
        "TRUNCATE public.invoice",
        "ALTER TABLE public.invoice ADD COLUMN x int",
        "CREATE TABLE public.x (id int)",
        "GRANT SELECT ON public.invoice TO PUBLIC",
        "SELECT * FROM public.invoice; DROP TABLE public.invoice",
        "SELECT total FROM public.invoice; SELECT total FROM public.invoice",
        "SELECT pg_read_file('/etc/passwd') FROM public.invoice",
        "SELECT pg_sleep(10) FROM public.invoice",
        "SELECT lo_export(1,'/tmp/a') FROM public.invoice",
        "SELECT * INTO temp newtable FROM public.invoice",
        "SELECT * FROM public.invoice FOR UPDATE",
        "SELECT * FROM pg_catalog.pg_authid",
        "SELECT * FROM information_schema.tables",
        "SELECT unknown_column FROM public.invoice",
        "SELECT total FROM public.track",
        "SELECT x.total FROM public.invoice i",
        "SELECT * FROM public.invoice -- comment",
        "/* hidden */ SELECT total FROM public.invoice",
        "WITH d AS (DELETE FROM public.invoice RETURNING *) SELECT * FROM d",
        "SELECT total FROM otherdb.public.invoice",
        "SELECT customer_id FROM public.invoice i JOIN public.customer c"
            + " ON c.customer_id = i.customer_id",
        "SELECT 1",
        "SELECT total FROM public.invoice WHERE total > ?",
        "EXEC xp_cmdshell 'dir'",
        ""
      })
  void unsafeOrOutOfScopeSqlIsRejected(String sql) {
    assertThatThrownBy(() -> pg(sql))
        .isInstanceOf(ApiException.class)
        .extracting("code")
        .isIn("SQL_SECURITY_ERROR", "SQL_VALIDATION_ERROR");
  }

  @Test
  void dialectMismatchIsRejected() {
    assertThatThrownBy(() -> pg("SELECT TOP 2 total FROM public.invoice"))
        .extracting("code")
        .isEqualTo("SQL_DIALECT_ERROR");
    assertThatThrownBy(
            () -> guard.validate("SELECT total FROM public.invoice LIMIT 2", "SQL_SERVER", tables))
        .extracting("code")
        .isEqualTo("SQL_DIALECT_ERROR");
    assertThatThrownBy(() -> pg("SELECT `total` FROM public.invoice"))
        .extracting("code")
        .isEqualTo("SQL_DIALECT_ERROR");
    assertThatThrownBy(() -> guard.validate("SELECT [total] FROM public.invoice", "MYSQL", tables))
        .extracting("code")
        .isEqualTo("SQL_DIALECT_ERROR");
  }

  @Test
  void eachDialectAcceptsItsOwnQuoting() {
    assertThat(pg("SELECT \"total\" FROM \"public\".\"invoice\" LIMIT 5").sql()).contains("LIMIT");
    assertThat(
            guard
                .validate("SELECT `total` FROM `public`.`invoice` LIMIT 5", "MYSQL", tables)
                .sourceTables())
        .hasSize(1);
    assertThat(
            guard
                .validate(
                    "SELECT TOP (5) [total] FROM [public].[invoice] ORDER BY [invoice_id]",
                    "SQL_SERVER",
                    tables)
                .sourceTables())
        .hasSize(1);
  }

  @Test
  void sqlServerBigCountIsOnlyAllowedOnSqlServer() {
    assertThat(
            guard
                .validate("SELECT COUNT_BIG(*) AS n FROM public.invoice", "SQL_SERVER", tables)
                .sql())
        .contains("COUNT_BIG");
    for (String engine : List.of("POSTGRESQL", "MYSQL"))
      assertThatThrownBy(
              () -> guard.validate("SELECT COUNT_BIG(*) FROM public.invoice", engine, tables))
          .isInstanceOf(ApiException.class);
  }

  @Test
  void overloadedQueriesAreRejected() {
    StringBuilder joins = new StringBuilder("SELECT i0.total FROM public.invoice i0");
    for (int i = 1; i <= 13; i++)
      joins
          .append(" JOIN public.invoice i")
          .append(i)
          .append(" ON i")
          .append(i)
          .append(".invoice_id = i0.invoice_id");
    assertThatThrownBy(() -> pg(joins.toString())).isInstanceOf(ApiException.class);
    String nested = "SELECT total FROM public.invoice";
    for (int i = 0; i < 60; i++) nested = "SELECT total FROM (" + nested + ") t" + i;
    String deep = nested;
    assertThatThrownBy(() -> pg(deep)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> pg("SELECT total FROM public.invoice WHERE " + "x".repeat(100001)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void rowLimitIsAppliedInEachDialect() {
    String sql = "SELECT total FROM public.invoice ORDER BY total DESC";
    assertThat(new PostgreSqlDialect().applySafeLimit(sql, 1001))
        .isEqualTo("SELECT * FROM (" + sql + ") AS nla_bounded LIMIT 1001");
    assertThat(new MySqlDialect().applySafeLimit(sql, 1001))
        .isEqualTo("SELECT * FROM (" + sql + ") AS nla_bounded LIMIT 1001");
    // SQL Server keeps ORDER BY semantics; JDBC setMaxRows bounds the result instead.
    assertThat(new SqlServerDialect().applySafeLimit(sql, 1001)).isEqualTo(sql);
    assertThatThrownBy(() -> new PostgreSqlDialect().applySafeLimit(sql, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deadlineCapsEveryTimeoutAndStopsExpiredWork() {
    QueryDeadline.start();
    try {
      assertThat(QueryDeadline.seconds(15)).isEqualTo(15);
      assertThat(QueryDeadline.seconds(600)).isLessThanOrEqualTo(60);
    } finally {
      QueryDeadline.clear();
    }
    assertThat(QueryDeadline.seconds(15)).isEqualTo(15);
  }
}
