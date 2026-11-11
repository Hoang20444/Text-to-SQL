package dev.nla.warehouse;

import dev.nla.common.Json;
import dev.nla.warehouse.dialect.MySqlDialect;
import dev.nla.warehouse.dialect.SqlDialect;
import java.util.Map;

public final class MySqlWarehouseAdapter extends JdbcWarehouseAdapter {
  public MySqlWarehouseAdapter(boolean tls) {
    this(tls, "");
  }

  public MySqlWarehouseAdapter(boolean tls, String rsaPublicKey) {
    super(tls, rsaPublicKey);
  }

  public String engine() {
    return "MYSQL";
  }

  public SqlDialect getSqlDialect() {
    return new MySqlDialect();
  }

  protected String jdbcUrl(Map<String, Object> w) {
    return "jdbc:mysql://"
        + Json.str(w, "host")
        + ":"
        + w.get("port")
        + "/"
        + Json.str(w, "database")
        + "?sslMode="
        + (tls ? "VERIFY_IDENTITY" : "DISABLED")
        + "&connectTimeout=5000&socketTimeout=20000&allowMultiQueries=false&allowLoadLocalInfile=false&allowUrlInLocalInfile=false&serverTimezone=UTC";
  }
}
