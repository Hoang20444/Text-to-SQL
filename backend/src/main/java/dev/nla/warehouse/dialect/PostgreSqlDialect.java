package dev.nla.warehouse.dialect;

public final class PostgreSqlDialect implements SqlDialect {
  public String name() {
    return "POSTGRESQL";
  }

  public String quoteIdentifier(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  public String applySafeLimit(String sql, int rows) {
    if (rows < 1) throw new IllegalArgumentException();
    return "SELECT * FROM (" + sql + ") AS nla_bounded LIMIT " + rows;
  }
}
