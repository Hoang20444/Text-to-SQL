package dev.nla.query;

import dev.nla.common.ApiException;
import dev.nla.common.Json;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.springframework.http.HttpStatus;

/**
 * Compiles a structured semantic selection (metrics + dimensions) into one SELECT for the warehouse
 * dialect (T-3.1.2, AC3). No LLM is involved: expressions come from the validated semantic model
 * and the user only picks names.
 *
 * <p>Rules that keep the numbers correct:
 *
 * <ul>
 *   <li>All selected metrics must be measured on the same single table (the base table / grain).
 *   <li>Every selected dimension must be listed in each metric's compatibleDimensions.
 *   <li>A dimension table is joined only through many-to-one (or one-to-one) relationships starting
 *       from the base table, so a join never repeats base rows and never multiplies a SUM.
 * </ul>
 *
 * Tables keep their own name as alias, so a metric written as {@code SUM(orders.total)} works
 * unchanged on every engine.
 */
public final class SemanticCompiler {
  /** Compiled statement plus the names it selected, in output column order. */
  public record Compiled(String sql, List<String> metrics, List<String> dimensions) {}

  private final UnaryOperator<String> quote;

  /** quote is the warehouse dialect's identifier quoting, e.g. "x", `x` or [x]. */
  public SemanticCompiler(UnaryOperator<String> quote) {
    this.quote = quote;
  }

  public Compiled compile(
      Map<String, Object> context, List<String> metricNames, List<String> dimensionNames) {
    var metrics = byName(Json.objects(context.get("metrics")), metricNames, "Metric");
    var dimensions = byName(Json.objects(context.get("dimensions")), dimensionNames, "Dimension");

    String base = baseTable(metrics);
    for (var metric : metrics) {
      var compatible =
          metric.get("compatibleDimensions") instanceof List<?> list ? list : List.of();
      for (var dimension : dimensions)
        if (!compatible.contains(dimension.get("name")))
          throw rejected(
              "Dimension '"
                  + Json.str(dimension, "name")
                  + "' không tương thích với metric '"
                  + Json.str(metric, "name")
                  + "'.");
    }

    // Join path from the base table to each dimension table, many-to-one edges only.
    var edges = safeEdges(Json.objects(context.get("relationships")));
    var joins = new LinkedHashMap<String, String>();
    for (var dimension : dimensions) {
      String target = key(Json.map(dimension.get("table")));
      if (target.equals(base)) continue;
      var path = path(base, target, edges);
      if (path == null)
        throw rejected(
            "Không có đường nối an toàn (nhiều-một) từ bảng của metric tới dimension '"
                + Json.str(dimension, "name")
                + "'; nối theo chiều này có thể nhân bản số liệu.");
      for (var edge : path) joins.putIfAbsent(edge.to(), edge.condition());
    }

    var aliases = new HashMap<String, String>();
    for (String table : concat(List.of(base), joins.keySet())) {
      String alias = table.substring(table.indexOf('.') + 1);
      if (aliases.containsValue(alias))
        throw rejected(
            "Hai bảng cùng tên '" + alias + "' ở schema khác nhau; không thể biên dịch.");
      aliases.put(table, alias);
    }

    List<String> select = new ArrayList<>();
    List<String> groupBy = new ArrayList<>();
    for (var dimension : dimensions) {
      String column =
          quote.apply(aliases.get(key(Json.map(dimension.get("table")))))
              + "."
              + quote.apply(Json.str(dimension, "column"));
      select.add(column + " AS " + quote.apply(Json.str(dimension, "name")));
      groupBy.add(column);
    }
    for (var metric : metrics)
      select.add(
          "(" + Json.str(metric, "expression") + ") AS " + quote.apply(Json.str(metric, "name")));

    StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", select));
    sql.append(" FROM ").append(table(base, aliases));
    for (var join : joins.entrySet())
      sql.append(" JOIN ")
          .append(table(join.getKey(), aliases))
          .append(" ON ")
          .append(condition(join.getValue(), aliases));
    if (!groupBy.isEmpty()) {
      sql.append(" GROUP BY ").append(String.join(", ", groupBy));
      // Stable order so the same query gives the same rows on every engine.
      sql.append(" ORDER BY ").append(String.join(", ", groupBy));
    }
    return new Compiled(
        sql.toString(),
        metrics.stream().map(m -> Json.str(m, "name")).toList(),
        dimensions.stream().map(d -> Json.str(d, "name")).toList());
  }

  private static List<Map<String, Object>> byName(
      List<Map<String, Object>> defined, List<String> names, String kind) {
    var index = new HashMap<String, Map<String, Object>>();
    defined.forEach(d -> index.put(Json.str(d, "name"), d));
    List<Map<String, Object>> picked = new ArrayList<>();
    for (String name : new LinkedHashSet<>(names)) {
      var definition = index.get(name);
      if (definition == null)
        throw ApiException.bad(kind + " '" + name + "' không có trong mô hình ngữ nghĩa.");
      picked.add(definition);
    }
    return picked;
  }

  private static String baseTable(List<Map<String, Object>> metrics) {
    Set<String> tables = new LinkedHashSet<>();
    for (var metric : metrics) {
      var refs = Json.objects(metric.get("tableRefs"));
      if (refs.size() != 1)
        throw rejected(
            "Metric '"
                + Json.str(metric, "name")
                + "' phải đo trên đúng một bảng gốc để biên dịch.");
      tables.add(key(refs.getFirst()));
    }
    if (tables.size() != 1)
      throw rejected("Các metric được chọn nằm ở bảng gốc khác nhau; hãy chạy riêng từng metric.");
    return tables.iterator().next();
  }

  /** A join step: from the many side to the one side, with an ON condition on table keys. */
  private record Edge(String from, String to, String condition) {}

  private static Map<String, List<Edge>> safeEdges(List<Map<String, Object>> relationships) {
    var edges = new HashMap<String, List<Edge>>();
    for (var relation : relationships) {
      var from = Json.map(relation.get("from"));
      var to = Json.map(relation.get("to"));
      String fromTable = key(Json.map(from.get("table")));
      String toTable = key(Json.map(to.get("table")));
      String condition =
          fromTable + "|" + Json.str(from, "column") + "|" + toTable + "|" + Json.str(to, "column");
      switch (Json.str(relation, "cardinality")) {
        case "MANY_TO_ONE" -> add(edges, new Edge(fromTable, toTable, condition));
        case "ONE_TO_MANY" -> add(edges, new Edge(toTable, fromTable, condition));
        case "ONE_TO_ONE" -> {
          add(edges, new Edge(fromTable, toTable, condition));
          add(edges, new Edge(toTable, fromTable, condition));
        }
        default -> {
          // MANY_TO_MANY would repeat base rows; never used for a join path.
        }
      }
    }
    return edges;
  }

  private static void add(Map<String, List<Edge>> edges, Edge edge) {
    edges.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
  }

  /** Shortest many-to-one path (breadth first), or null when there is none. */
  private static List<Edge> path(String start, String target, Map<String, List<Edge>> edges) {
    var previous = new HashMap<String, Edge>();
    var queue = new ArrayDeque<String>(List.of(start));
    Set<String> seen = new LinkedHashSet<>(List.of(start));
    while (!queue.isEmpty()) {
      String table = queue.poll();
      if (table.equals(target)) {
        List<Edge> path = new ArrayList<>();
        for (String at = target; !at.equals(start); at = previous.get(at).from())
          path.addFirst(previous.get(at));
        return path;
      }
      for (var edge : edges.getOrDefault(table, List.of()))
        if (seen.add(edge.to())) {
          previous.put(edge.to(), edge);
          queue.add(edge.to());
        }
    }
    return null;
  }

  private String table(String key, Map<String, String> aliases) {
    int dot = key.indexOf('.');
    return quote.apply(key.substring(0, dot))
        + "."
        + quote.apply(key.substring(dot + 1))
        + " "
        + quote.apply(aliases.get(key));
  }

  private String condition(String condition, Map<String, String> aliases) {
    String[] part = condition.split("\\|", -1);
    return quote.apply(aliases.get(part[0]))
        + "."
        + quote.apply(part[1])
        + " = "
        + quote.apply(aliases.get(part[2]))
        + "."
        + quote.apply(part[3]);
  }

  private static List<String> concat(List<String> first, Set<String> rest) {
    List<String> all = new ArrayList<>(first);
    all.addAll(rest);
    return all;
  }

  private static String key(Map<String, Object> table) {
    return Json.str(table, "schema") + "." + Json.str(table, "name");
  }

  private static ApiException rejected(String message) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SEMANTIC_MODEL_INVALID", message);
  }
}
