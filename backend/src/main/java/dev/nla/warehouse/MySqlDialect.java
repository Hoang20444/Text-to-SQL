package dev.nla.warehouse;

public final class MySqlDialect implements SqlDialect {
  public String name() {
    return "MYSQL";
  }

  public String quoteIdentifier(String value) {
    return "`" + value.replace("`", "``") + "`";
  }

  public String applySafeLimit(String sql, int rows) {
    if (rows < 1) throw new IllegalArgumentException();
    return "SELECT * FROM (" + sql + ") AS nla_bounded LIMIT " + rows;
  }
}
