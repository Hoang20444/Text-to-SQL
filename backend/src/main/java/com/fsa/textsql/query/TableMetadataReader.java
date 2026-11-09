package com.fsa.textsql.query;

import com.fsa.textsql.common.ApiException;
import com.fsa.textsql.dataset.TableRef;
import com.fsa.textsql.warehouse.Engine;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Reads live column metadata for the dataset's allowed tables only, in the semanticContext table
 * shape. Only names and types are read; no sample rows are sent to the AI service.
 */
@Component
public class TableMetadataReader {

  public List<Map<String, Object>> read(
      Connection connection, Engine engine, String database, List<TableRef> allowedTables)
      throws SQLException {
    DatabaseMetaData meta = connection.getMetaData();
    List<Map<String, Object>> tables = new ArrayList<>();
    for (TableRef table : allowedTables) {
      // MySQL exposes databases as catalogs; PostgreSQL and SQL Server use schemas.
      String catalog =
          switch (engine) {
            case MYSQL -> table.schema();
            case SQL_SERVER -> database;
            case POSTGRESQL -> null;
          };
      String schema = engine == Engine.MYSQL ? null : table.schema();

      List<Map<String, Object>> columns = new ArrayList<>();
      try (ResultSet rs = meta.getColumns(catalog, schema, table.name(), "%")) {
        while (rs.next()) {
          if (!table.name().equals(rs.getString("TABLE_NAME"))) {
            continue; // the pattern may match similarly named tables (e.g. '_' wildcard)
          }
          Map<String, Object> column = new LinkedHashMap<>();
          column.put("name", rs.getString("COLUMN_NAME"));
          column.put("dataType", SqlTypes.canonical(rs.getInt("DATA_TYPE")));
          column.put("nullable", rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
          column.put("description", "");
          column.put("synonyms", List.of());
          columns.add(column);
        }
      }
      if (columns.isEmpty()) {
        throw new ApiException(
            HttpStatus.CONFLICT,
            "SCHEMA_MISMATCH",
            "Bảng được cấu hình không còn tồn tại trong kho dữ liệu.",
            false,
            Map.of("table", table.schema() + "." + table.name()));
      }

      TreeMap<Integer, String> primaryKey = new TreeMap<>();
      try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, table.name())) {
        while (rs.next()) {
          primaryKey.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }
      }

      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("schema", table.schema());
      entry.put("name", table.name());
      entry.put("description", "");
      entry.put("grain", "");
      entry.put("columns", columns);
      entry.put("primaryKey", List.copyOf(primaryKey.values()));
      tables.add(entry);
    }
    return tables;
  }
}
