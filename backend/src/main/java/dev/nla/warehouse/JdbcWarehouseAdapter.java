package dev.nla.warehouse;

import dev.nla.Json;
import java.sql.*;
import java.time.*;
import java.util.*;

public abstract class JdbcWarehouseAdapter implements WarehouseAdapter {
  protected final boolean tls;

  protected JdbcWarehouseAdapter(boolean tls) {
    this.tls = tls;
  }

  protected abstract String jdbcUrl(Map<String, Object> w);

  public Connection connect(Map<String, Object> w, String password) throws SQLException {
    Properties props = new Properties();
    props.setProperty("user", Json.str(w, "username"));
    props.setProperty("password", password);
    props.setProperty("connectTimeout", engine().equals("POSTGRESQL") ? "5" : "5000");
    props.setProperty("socketTimeout", engine().equals("POSTGRESQL") ? "20" : "20000");
    if (engine().equals("MYSQL") && !tls) {
      String key = System.getenv("NLA_MYSQL_RSA_PUBLIC_KEY");
      if (key != null && !key.isBlank()) props.setProperty("serverRSAPublicKeyFile", key);
    }
    return DriverManager.getConnection(jdbcUrl(w), props);
  }

  public boolean testConnection(Map<String, Object> w, String password) throws SQLException {
    try (Connection c = connect(w, password)) {
      return c.isValid(5);
    }
  }

  public Map<String, Boolean> getCapabilities() {
    return Map.of("readOnlyQuery", true, "metadataDiscovery", true);
  }

  public String quoteIdentifier(String id) {
    return getSqlDialect().quoteIdentifier(id);
  }

  public String normalizeIdentifier(Connection c, String id, boolean quoted) throws SQLException {
    var m = c.getMetaData();
    if (quoted) return id;
    if (m.storesLowerCaseIdentifiers()) return id.toLowerCase(Locale.ROOT);
    if (m.storesUpperCaseIdentifiers()) return id.toUpperCase(Locale.ROOT);
    return id;
  }

  protected String catalog(Connection c, String schema) throws SQLException {
    return engine().equals("MYSQL") ? schema : c.getCatalog();
  }

  protected String schemaPattern(String schema) {
    return engine().equals("MYSQL") ? null : schema;
  }

  public List<String> discoverSchemas(Connection c) throws SQLException {
    List<String> names = new ArrayList<>();
    if (engine().equals("MYSQL")) {
      try (ResultSet catalogs = c.getMetaData().getCatalogs()) {
        while (catalogs.next()) {
          String name = catalogs.getString("TABLE_CAT");
          if (!Set.of("information_schema", "mysql", "performance_schema", "sys").contains(name)) {
            names.add(name);
          }
        }
      }
      return names;
    }
    try (ResultSet rs = c.getMetaData().getSchemas()) {
      while (rs.next()) {
        String n = rs.getString("TABLE_SCHEM");
        if (!Set.of("information_schema", "sys", "guest").contains(n)
            && !n.startsWith("pg_")
            && !n.startsWith("db_")) names.add(n);
      }
    }
    return names;
  }

  public List<Map<String, Object>> discoverTables(Connection c, Set<String> requested)
      throws SQLException {
    List<Map<String, Object>> tables = new ArrayList<>();
    for (String schema : discoverSchemas(c)) {
      if (!requested.isEmpty() && !requested.contains(schema)) continue;
      var descriptions = NativeMetadataDescriptions.load(c, engine(), schema);
      try (ResultSet rs =
          c.getMetaData()
              .getTables(
                  catalog(c, schema), schemaPattern(schema), "%", new String[] {"TABLE", "VIEW"})) {
        while (rs.next()) {
          String name = rs.getString("TABLE_NAME");
          List<String> pk = new ArrayList<>();
          try (ResultSet keys =
              c.getMetaData().getPrimaryKeys(catalog(c, schema), schemaPattern(schema), name)) {
            while (keys.next()) pk.add(keys.getString("COLUMN_NAME"));
          }
          String id =
              Base64.getUrlEncoder()
                  .withoutPadding()
                  .encodeToString(
                      (schema + "\u0000" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
          tables.add(
              Json.obj(
                  "tableId",
                  id,
                  "schema",
                  schema,
                  "name",
                  name,
                  "logicalName",
                  name,
                  "description",
                  descriptions.table(name, rs.getString("REMARKS")),
                  "grain",
                  pk.isEmpty() ? "Physical table/view" : "One row per " + String.join(", ", pk),
                  "columns",
                  discoverColumns(c, schema, name, descriptions),
                  "queryable",
                  true,
                  "category",
                  schema,
                  "status",
                  "PUBLISHED",
                  "primaryKey",
                  pk,
                  "foreignKeys",
                  discoverRelationships(c, schema, name)));
        }
      }
    }
    return tables;
  }

  public List<Map<String, Object>> discoverColumns(Connection c, String schema, String table)
      throws SQLException {
    return discoverColumns(c, schema, table, NativeMetadataDescriptions.load(c, engine(), schema));
  }

  private List<Map<String, Object>> discoverColumns(
      Connection c, String schema, String table, NativeMetadataDescriptions descriptions)
      throws SQLException {
    List<Map<String, Object>> columns = new ArrayList<>();
    try (ResultSet rs =
        c.getMetaData().getColumns(catalog(c, schema), schemaPattern(schema), table, "%")) {
      while (rs.next())
        columns.add(
            Json.obj(
                "name",
                rs.getString("COLUMN_NAME"),
                "dataType",
                canonicalType(rs.getInt("DATA_TYPE")),
                "nullable",
                rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                "description",
                descriptions.column(table, rs.getString("COLUMN_NAME"), rs.getString("REMARKS")),
                "synonyms",
                List.of()));
    }
    return columns;
  }

  public List<Map<String, Object>> discoverRelationships(Connection c, String schema, String table)
      throws SQLException {
    List<Map<String, Object>> rel = new ArrayList<>();
    try (ResultSet rs =
        c.getMetaData().getImportedKeys(catalog(c, schema), schemaPattern(schema), table)) {
      while (rs.next()) {
        String targetSchema =
            engine().equals("MYSQL") ? rs.getString("PKTABLE_CAT") : rs.getString("PKTABLE_SCHEM");
        rel.add(
            Json.obj(
                "from",
                Json.obj(
                    "table",
                    Json.obj("schema", schema, "name", table),
                    "column",
                    rs.getString("FKCOLUMN_NAME")),
                "to",
                Json.obj(
                    "table",
                    Json.obj("schema", targetSchema, "name", rs.getString("PKTABLE_NAME")),
                    "column",
                    rs.getString("PKCOLUMN_NAME")),
                "cardinality",
                "MANY_TO_ONE"));
      }
    }
    return rel;
  }

  public static String canonicalType(int type) {
    return switch (type) {
      case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> "INTEGER";
      case Types.BIGINT -> "BIGINT";
      case Types.NUMERIC, Types.DECIMAL -> "DECIMAL";
      case Types.FLOAT, Types.DOUBLE, Types.REAL -> "FLOAT";
      case Types.BOOLEAN, Types.BIT -> "BOOLEAN";
      case Types.DATE -> "DATE";
      case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP";
      default -> "STRING";
    };
  }
}
