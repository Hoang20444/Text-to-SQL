package dev.nla.query;

import dev.nla.common.Json;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Decides the final chart of a query from the AI recommendation and the real result (T-4.3.2,
 * US-4.3 AC2).
 *
 * <p>The recommendation is only a suggestion: Python knows the SQL aliases but not the data types
 * the warehouse actually returned. Here every referenced column must exist in the result, every
 * series must be numeric and the shape must fit the chart type (domain.md, ChartSpec). Anything
 * that does not fit becomes a {@code table} with a Vietnamese {@code reason}, so the UI never draws
 * a broken chart. The output is rebuilt from allowlisted fields only.
 */
final class ChartSpecs {
  static final Set<String> TYPES =
      Set.of("table", "number", "bar", "grouped-bar", "line", "area", "pie");
  static final Set<String> NUMERIC = Set.of("INTEGER", "BIGINT", "DECIMAL", "FLOAT");
  static final String DEFAULT_TITLE = "Kết quả truy vấn";
  static final int MAX_TITLE = 500;
  static final int MAX_LABEL = 500;
  static final int MAX_REASON = 1000;

  private ChartSpecs() {}

  /**
   * Recommendation for a structured semantic query, where no AI is called: the compiler aliases
   * every column with the metric or dimension name. One dimension gives a line chart when it is a
   * time column and a bar (or grouped-bar for several metrics) otherwise; no dimension gives a
   * number; more dimensions stay a table. The result is still checked by {@link #decide}.
   */
  static Map<String, Object> suggest(
      List<String> metrics, List<String> dimensions, Map<String, Object> result) {
    String title =
        String.join(", ", metrics)
            + (dimensions.isEmpty() ? "" : " theo " + String.join(", ", dimensions));
    var series = metrics.stream().map(m -> Json.obj("column", m, "label", m)).toList();
    if (dimensions.isEmpty())
      return metrics.size() == 1
          ? chart("number", title, null, series, null)
          : table(title, "Nhiều chỉ số không có chiều phân tích; hiển thị dạng bảng.");
    if (dimensions.size() > 1)
      return table(title, "Nhiều chiều phân tích cùng lúc; hiển thị dạng bảng.");
    String x = dimensions.get(0);
    boolean time =
        Json.objects(result.get("columns")).stream()
            .anyMatch(
                c -> Json.str(c, "name").equals(x) && Json.str(c, "semanticRole").equals("TIME"));
    String type = time ? "line" : metrics.size() == 1 ? "bar" : "grouped-bar";
    return chart(type, title, x, series, null);
  }

  static Map<String, Object> decide(Object recommendation, Map<String, Object> result) {
    var columns = Json.objects(result.get("columns"));
    var rows = Json.objects(result.get("rows"));
    var spec = Json.map(recommendation);
    String title = title(spec.get("title"));
    // One numeric value is shown as a KPI card whatever the AI suggested (US-4.3 AC1).
    if (rows.size() == 1 && columns.size() == 1 && numeric(columns.get(0)))
      return chart("number", title, null, List.of(series(columns.get(0), null)), null);
    if (!(recommendation instanceof Map<?, ?>))
      return table(title, "AI không gợi ý biểu đồ; hiển thị dạng bảng.");
    String type = Json.str(spec, "type");
    if (!TYPES.contains(type))
      return table(title, "Loại biểu đồ '" + type + "' không được hỗ trợ.");
    if (type.equals("table"))
      return table(
          title,
          spec.get("reason") instanceof String r && !r.isBlank() && r.length() <= MAX_REASON
              ? r
              : null);
    if (rows.isEmpty()) return table(title, "Không có dòng dữ liệu để vẽ biểu đồ.");

    Map<String, Object> x = null;
    if (spec.get("x") != null) {
      x = column(columns, spec.get("x"));
      if (x == null)
        return table(title, "Cột trục X '" + spec.get("x") + "' không có trong kết quả.");
    }
    if (!(spec.get("series") instanceof List<?> requested) || requested.isEmpty())
      return table(title, "Biểu đồ " + type + " cần ít nhất một cột số liệu.");
    List<Map<String, Object>> series = new ArrayList<>();
    Set<String> used = new HashSet<>();
    if (x != null) used.add(Json.str(x, "name"));
    for (Object item : requested) {
      var wanted = Json.map(item);
      var col = column(columns, wanted.get("column"));
      if (col == null)
        return table(title, "Cột số liệu '" + wanted.get("column") + "' không có trong kết quả.");
      String name = Json.str(col, "name");
      if (!used.add(name)) return table(title, "Cột '" + name + "' bị dùng lặp lại trong biểu đồ.");
      if (!numeric(col))
        return table(
            title,
            "Cột '" + name + "' kiểu " + col.get("dataType") + " không phải số nên không vẽ được.");
      if (rows.stream().allMatch(r -> r.get(name) == null))
        return table(title, "Cột '" + name + "' không có giá trị nào để vẽ.");
      series.add(series(col, wanted.get("label")));
    }

    String problem = shape(type, x, series, rows);
    if (problem != null) return table(title, problem);
    return chart(type, title, type.equals("number") ? null : Json.str(x, "name"), series, null);
  }

  /** Shape rules of ChartSpec per type; null when the chart fits. */
  private static String shape(
      String type,
      Map<String, Object> x,
      List<Map<String, Object>> series,
      List<Map<String, Object>> rows) {
    switch (type) {
      case "number" -> {
        if (rows.size() != 1 || series.size() != 1)
          return "Thẻ số liệu chỉ dùng cho một giá trị duy nhất (1 dòng, 1 cột số).";
        return null;
      }
      case "bar", "pie" -> {
        if (x == null) return "Biểu đồ " + type + " cần cột danh mục cho trục X.";
        if (series.size() != 1)
          return "Biểu đồ " + type + " chỉ vẽ một cột số liệu; dùng grouped-bar cho nhiều cột.";
        if (type.equals("pie")) return pie(rows, Json.str(series.get(0), "column"));
        return null;
      }
      case "grouped-bar", "line", "area" -> {
        if (x == null) return "Biểu đồ " + type + " cần cột cho trục X.";
        if (type.equals("grouped-bar") && series.size() < 2)
          return "Biểu đồ cột ghép cần ít nhất hai cột số liệu.";
        return null;
      }
      default -> {
        return "Loại biểu đồ '" + type + "' không được hỗ trợ.";
      }
    }
  }

  /** A pie only makes sense for parts of a whole: no negative values and something to share. */
  private static String pie(List<Map<String, Object>> rows, String column) {
    BigDecimal total = BigDecimal.ZERO;
    for (var row : rows) {
      Object value = row.get(column);
      if (value == null) continue;
      BigDecimal number = new BigDecimal(value.toString());
      if (number.signum() < 0)
        return "Biểu đồ tròn không vẽ được giá trị âm ở cột '" + column + "'.";
      total = total.add(number);
    }
    return total.signum() == 0 ? "Biểu đồ tròn cần tổng giá trị lớn hơn 0." : null;
  }

  /** Exact name first, then case-insensitive: PostgreSQL folds unquoted aliases to lower case. */
  private static Map<String, Object> column(List<Map<String, Object>> columns, Object wanted) {
    if (!(wanted instanceof String name) || name.isBlank()) return null;
    for (var c : columns) if (Json.str(c, "name").equals(name)) return c;
    var matches = columns.stream().filter(c -> Json.str(c, "name").equalsIgnoreCase(name)).toList();
    return matches.size() == 1 ? matches.get(0) : null;
  }

  private static boolean numeric(Map<String, Object> column) {
    return NUMERIC.contains(Json.str(column, "dataType"));
  }

  private static Map<String, Object> series(Map<String, Object> column, Object label) {
    String text =
        label instanceof String s && !s.isBlank() && s.length() <= MAX_LABEL
            ? s
            : Objects.toString(column.get("label"), Json.str(column, "name"));
    return Json.obj("column", Json.str(column, "name"), "label", text);
  }

  private static String title(Object value) {
    return value instanceof String s && !s.isBlank() && s.length() <= MAX_TITLE ? s : DEFAULT_TITLE;
  }

  private static Map<String, Object> table(String title, String reason) {
    return chart("table", title, null, List.of(), reason);
  }

  private static Map<String, Object> chart(
      String type, String title, String x, List<Map<String, Object>> series, String reason) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("type", type);
    out.put("title", title);
    if (x != null) out.put("x", x);
    out.put("series", series);
    if (reason != null) out.put("reason", reason);
    return out;
  }
}
