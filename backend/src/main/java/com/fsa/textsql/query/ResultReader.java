package com.fsa.textsql.query;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts a JDBC result into the contract shape. DECIMAL and BIGINT become decimal strings (no
 * precision loss in JSON), FLOAT stays a finite number, timestamps become UTC ISO-8601. Timestamps
 * without a time zone are interpreted in the dataset time zone.
 */
final class ResultReader {

  static final int PAGE_SIZE = 100;

  private ResultReader() {}

  /**
   * Reads at most {@code maxRows}; one extra row is fetched (via setMaxRows) to detect truncation.
   */
  static QueryResult read(
      ResultSet rs,
      int maxRows,
      ZoneId datasetZone,
      List<String> selectedMetrics,
      List<String> selectedDimensions)
      throws SQLException {
    ResultSetMetaData meta = rs.getMetaData();
    int count = meta.getColumnCount();
    List<QueryResult.Column> columns = new ArrayList<>();
    String[] names = new String[count];
    int[] jdbcTypes = new int[count];
    Set<String> used = new HashSet<>();
    for (int i = 1; i <= count; i++) {
      String label = meta.getColumnLabel(i);
      String name = uniqueName(label, used);
      names[i - 1] = name;
      jdbcTypes[i - 1] = meta.getColumnType(i);
      String dataType = SqlTypes.canonical(jdbcTypes[i - 1]);
      columns.add(
          new QueryResult.Column(
              name,
              label,
              dataType,
              role(label, dataType, selectedMetrics, selectedDimensions),
              null));
    }

    List<Map<String, Object>> rows = new ArrayList<>();
    boolean truncated = false;
    while (rs.next()) {
      if (rows.size() == maxRows) {
        truncated = true;
        break;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      for (int i = 1; i <= count; i++) {
        row.put(names[i - 1], value(rs, i, jdbcTypes[i - 1], datasetZone));
      }
      rows.add(row);
    }
    int returned = rows.size();
    List<Map<String, Object>> firstPage =
        List.copyOf(rows.subList(0, Math.min(PAGE_SIZE, returned)));
    return new QueryResult(columns, firstPage, returned, 1, PAGE_SIZE, truncated);
  }

  private static String uniqueName(String label, Set<String> used) {
    String base = label == null || label.isBlank() ? "column" : label;
    String name = base;
    int n = 2;
    while (!used.add(name)) {
      name = base + "_" + n++;
    }
    return name;
  }

  private static String role(
      String label, String dataType, List<String> metrics, List<String> dimensions) {
    if (metrics.contains(label)) {
      return "METRIC";
    }
    if (dimensions.contains(label)) {
      return dataType.equals("DATE") || dataType.equals("TIMESTAMP") ? "TIME" : "DIMENSION";
    }
    if (dataType.equals("DATE") || dataType.equals("TIMESTAMP")) {
      return "TIME";
    }
    return "OTHER";
  }

  private static Object value(ResultSet rs, int i, int jdbcType, ZoneId zone) throws SQLException {
    Object raw = rs.getObject(i);
    if (raw == null) {
      return null;
    }
    return switch (SqlTypes.canonical(jdbcType)) {
      case "BOOLEAN" -> rs.getBoolean(i);
      case "INTEGER" -> rs.getLong(i);
      case "BIGINT" ->
          raw instanceof BigDecimal d ? d.toPlainString() : String.valueOf(rs.getLong(i));
      case "DECIMAL" -> rs.getBigDecimal(i).toPlainString();
      case "FLOAT" -> {
        double d = rs.getDouble(i);
        yield Double.isFinite(d) ? d : null;
      }
      case "DATE" -> rs.getObject(i, LocalDate.class).toString();
      case "TIMESTAMP" -> timestamp(rs, i, jdbcType, zone);
      default -> rs.getString(i);
    };
  }

  private static String timestamp(ResultSet rs, int i, int jdbcType, ZoneId zone)
      throws SQLException {
    // Instant.toString() always prints full seconds with a trailing Z (e.g. 2009-01-01T00:00:00Z).
    if (jdbcType == Types.TIMESTAMP_WITH_TIMEZONE) {
      return rs.getObject(i, OffsetDateTime.class).toInstant().toString();
    }
    LocalDateTime local = rs.getObject(i, LocalDateTime.class);
    return local.atZone(zone).toInstant().toString();
  }
}
