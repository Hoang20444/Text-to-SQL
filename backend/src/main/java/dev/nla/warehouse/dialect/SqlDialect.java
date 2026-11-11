package dev.nla.warehouse.dialect;

public interface SqlDialect {
  String name();

  String quoteIdentifier(String identifier);

  String applySafeLimit(String sql, int maxRows);
}
