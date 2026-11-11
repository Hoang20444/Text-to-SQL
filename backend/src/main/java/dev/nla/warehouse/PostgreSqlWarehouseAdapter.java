package dev.nla.warehouse;

import dev.nla.common.Json;
import dev.nla.warehouse.dialect.PostgreSqlDialect;
import dev.nla.warehouse.dialect.SqlDialect;
import java.util.Map;

public final class PostgreSqlWarehouseAdapter extends JdbcWarehouseAdapter {
  public PostgreSqlWarehouseAdapter(boolean tls) {
    super(tls);
  }

  public String engine() {
    return "POSTGRESQL";
  }

  public SqlDialect getSqlDialect() {
    return new PostgreSqlDialect();
  }

  protected String jdbcUrl(Map<String, Object> w) {
    return "jdbc:postgresql://"
        + Json.str(w, "host")
        + ":"
        + w.get("port")
        + "/"
        + Json.str(w, "database")
        + "?sslmode="
        + (tls ? "verify-full" : "disable");
  }
}
