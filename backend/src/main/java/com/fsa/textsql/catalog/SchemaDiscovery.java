package com.fsa.textsql.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsa.textsql.common.ApiException;
import com.fsa.textsql.dataset.TableRef;
import com.fsa.textsql.query.SqlTypes;
import com.fsa.textsql.warehouse.Engine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Reads the physical structure of a warehouse through JDBC metadata, with engine-specific rules for
 * PostgreSQL, MySQL (databases are catalogs) and SQL Server (schemas inside one database). System
 * schemas are skipped. The result is bounded to keep scans predictable.
 */
@Component
public class SchemaDiscovery {

  static final int MAX_TABLES = 2000;

  private static final Set<String> PG_SYSTEM = Set.of("pg_catalog", "information_schema");
  private static final Set<String> MYSQL_SYSTEM =
      Set.of("mysql", "information_schema", "performance_schema", "sys");
  private static final Set<String> MSSQL_SYSTEM =
      Set.of(
          "sys",
          "information_schema",
          "guest",
          "db_owner",
          "db_accessadmin",
          "db_securityadmin",
          "db_ddladmin",
          "db_backupoperator",
          "db_datareader",
          "db_datawriter",
          "db_denydatareader",
          "db_denydatawriter");

  private final ObjectMapper json;

  public SchemaDiscovery(ObjectMapper json) {
    this.json = json;
  }

  public SchemaSnapshot discover(Connection connection, Engine engine, String database)
      throws SQLException {
    DatabaseMetaData meta = connection.getMetaData();
    List<String> schemas = schemas(meta, engine, database);
    List<SchemaSnapshot.Table> tables = new ArrayList<>();
    for (String schema : schemas) {
      String catalog = catalog(engine, database, schema);
      String schemaPattern = engine == Engine.MYSQL ? null : schema;

      Map<String, String> tableTypes = new TreeMap<>();
      try (ResultSet rs =
          meta.getTables(catalog, schemaPattern, "%", new String[] {"TABLE", "VIEW"})) {
        while (rs.next()) {
          tableTypes.put(rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE"));
        }
      }
      if (tables.size() + tableTypes.size() > MAX_TABLES) {
        throw new ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "DISCOVERY_LIMIT",
            "Kho dữ liệu có quá nhiều bảng để quét (tối đa " + MAX_TABLES + ").",
            false,
            Map.of("maxTables", MAX_TABLES));
      }

      Map<String, List<SchemaSnapshot.Column>> columns = columns(meta, catalog, schemaPattern);
      for (Map.Entry<String, String> entry : tableTypes.entrySet()) {
        String table = entry.getKey();
        tables.add(
            new SchemaSnapshot.Table(
                schema,
                table,
                entry.getValue().toUpperCase(Locale.ROOT).contains("VIEW") ? "VIEW" : "TABLE",
                columns.getOrDefault(table, List.of()),
                primaryKey(meta, catalog, schemaPattern, table),
                foreignKeys(meta, engine, catalog, schemaPattern, schema, table)));
      }
    }
    tables.sort(
        Comparator.comparing(SchemaSnapshot.Table::schema)
            .thenComparing(SchemaSnapshot.Table::name));
    return new SchemaSnapshot(schemas, tables);
  }

  /** Stable identifier of the structure: same schema => same version on every engine run. */
  public String schemaVersion(SchemaSnapshot snapshot) {
    try {
      byte[] canonical = json.writeValueAsString(snapshot).getBytes(StandardCharsets.UTF_8);
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical);
      return "sha256:" + HexFormat.of().formatHex(hash, 0, 8);
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("Cannot compute schema version", e);
    }
  }

  private static List<String> schemas(DatabaseMetaData meta, Engine engine, String database)
      throws SQLException {
    List<String> names = new ArrayList<>();
    switch (engine) {
      case MYSQL -> {
        try (ResultSet rs = meta.getCatalogs()) {
          while (rs.next()) {
            names.add(rs.getString("TABLE_CAT"));
          }
        }
      }
      case SQL_SERVER -> {
        try (ResultSet rs = meta.getSchemas(database, "%")) {
          while (rs.next()) {
            names.add(rs.getString("TABLE_SCHEM"));
          }
        }
      }
      case POSTGRESQL -> {
        try (ResultSet rs = meta.getSchemas()) {
          while (rs.next()) {
            names.add(rs.getString("TABLE_SCHEM"));
          }
        }
      }
    }
    return names.stream().filter(name -> !isSystem(engine, name)).sorted().distinct().toList();
  }

  static boolean isSystem(Engine engine, String schema) {
    String lower = schema.toLowerCase(Locale.ROOT);
    return switch (engine) {
      case POSTGRESQL ->
          PG_SYSTEM.contains(lower) || lower.startsWith("pg_toast") || lower.startsWith("pg_temp");
      case MYSQL -> MYSQL_SYSTEM.contains(lower);
      case SQL_SERVER -> MSSQL_SYSTEM.contains(lower);
    };
  }

  private static String catalog(Engine engine, String database, String schema) {
    return switch (engine) {
      case MYSQL -> schema;
      case SQL_SERVER -> database;
      case POSTGRESQL -> null;
    };
  }

  /** All columns of a schema in one metadata call, grouped by table in ordinal order. */
  private static Map<String, List<SchemaSnapshot.Column>> columns(
      DatabaseMetaData meta, String catalog, String schemaPattern) throws SQLException {
    Map<String, TreeMap<Integer, SchemaSnapshot.Column>> byTable = new LinkedHashMap<>();
    try (ResultSet rs = meta.getColumns(catalog, schemaPattern, "%", "%")) {
      while (rs.next()) {
        byTable
            .computeIfAbsent(rs.getString("TABLE_NAME"), t -> new TreeMap<>())
            .put(
                rs.getInt("ORDINAL_POSITION"),
                new SchemaSnapshot.Column(
                    rs.getString("COLUMN_NAME"),
                    SqlTypes.canonical(rs.getInt("DATA_TYPE")),
                    rs.getString("TYPE_NAME"),
                    rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
      }
    }
    Map<String, List<SchemaSnapshot.Column>> result = new LinkedHashMap<>();
    byTable.forEach((table, ordered) -> result.put(table, List.copyOf(ordered.values())));
    return result;
  }

  private static List<String> primaryKey(
      DatabaseMetaData meta, String catalog, String schemaPattern, String table)
      throws SQLException {
    TreeMap<Integer, String> keys = new TreeMap<>();
    try (ResultSet rs = meta.getPrimaryKeys(catalog, schemaPattern, table)) {
      while (rs.next()) {
        keys.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
      }
    }
    return List.copyOf(keys.values());
  }

  private static List<SchemaSnapshot.ForeignKey> foreignKeys(
      DatabaseMetaData meta,
      Engine engine,
      String catalog,
      String schemaPattern,
      String schema,
      String table)
      throws SQLException {
    List<SchemaSnapshot.ForeignKey> keys = new ArrayList<>();
    try (ResultSet rs = meta.getImportedKeys(catalog, schemaPattern, table)) {
      while (rs.next()) {
        // MySQL reports the referenced database as PKTABLE_CAT; others use PKTABLE_SCHEM.
        String targetSchema =
            engine == Engine.MYSQL ? rs.getString("PKTABLE_CAT") : rs.getString("PKTABLE_SCHEM");
        keys.add(
            new SchemaSnapshot.ForeignKey(
                rs.getString("FK_NAME"),
                new SchemaSnapshot.Endpoint(
                    new TableRef(schema, table), rs.getString("FKCOLUMN_NAME")),
                new SchemaSnapshot.Endpoint(
                    new TableRef(targetSchema, rs.getString("PKTABLE_NAME")),
                    rs.getString("PKCOLUMN_NAME"))));
      }
    }
    keys.sort(
        Comparator.comparing((SchemaSnapshot.ForeignKey k) -> String.valueOf(k.constraint()))
            .thenComparing(k -> k.from().column()));
    return keys;
  }
}
