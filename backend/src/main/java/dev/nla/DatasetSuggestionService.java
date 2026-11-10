package dev.nla;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Questions are proposals derived from the active metadata, never fabricated result values. */
@Service
public final class DatasetSuggestionService {
  private final Store store;
  private final CatalogService catalog;
  private final SqlGuard guard;
  private static final Set<String> NUMBERS = Set.of("INTEGER", "BIGINT", "DECIMAL", "FLOAT");

  DatasetSuggestionService(Store store, CatalogService catalog, SqlGuard guard) {
    this.store = store;
    this.catalog = catalog;
    this.guard = guard;
  }

  public Object suggestions(String id, Map<String, String> parameters) {
    var dataset = store.dataset(id);
    var warehouse = store.warehouse(Json.str(dataset, "warehouseId"));
    if (!"ACTIVE".equals(dataset.get("status")) || !"ACTIVE".equals(warehouse.get("status"))) {
      throw new ApiException(
          HttpStatus.CONFLICT, "DATASET_UNAVAILABLE", "Dataset/warehouse chưa hoạt động.");
    }
    var context = catalog.context(id);
    return Page.of(
        derive(context, Json.str(warehouse, "engine"), guard),
        parameters,
        "question",
        Set.of("question", "category", "origin", "suggestionId"));
  }

  static List<Map<String, Object>> derive(
      Map<String, Object> context, String engine, SqlGuard guard) {
    Map<String, Map<String, Object>> output = new LinkedHashMap<>();
    var tables = Json.objects(context.get("tables"));
    var metrics = Json.objects(context.get("metrics"));
    var dimensions = Json.objects(context.get("dimensions"));
    Map<String, Map<String, Object>> dimensionNames = new LinkedHashMap<>();
    for (var dimension : dimensions) {
      dimensionNames.put(Json.str(dimension, "name"), dimension);
    }
    for (var table : tables) {
      var reference = Json.obj("schema", table.get("schema"), "name", table.get("name"));
      String name = Json.str(table, "schema") + "." + Json.str(table, "name");
      String category = "Schema · " + Json.str(table, "schema");
      add(
          output,
          "Có bao nhiêu bản ghi trong bảng " + name + "?",
          "Đề xuất từ bảng vật lý hiện tại; đếm số dòng, không giả định ý nghĩa nghiệp vụ.",
          category,
          "PHYSICAL_SCHEMA",
          "number",
          List.of(reference),
          List.of(),
          List.of(),
          engine);
      Set<String> keys = new HashSet<>();
      if (table.get("primaryKey") instanceof List<?> primary) {
        primary.forEach(key -> keys.add(Objects.toString(key)));
      }
      for (var relationship : Json.objects(table.get("foreignKeys"))) {
        keys.add(Json.str(Json.map(relationship.get("from")), "column"));
      }
      var columns = Json.objects(table.get("columns"));
      var categories =
          columns.stream()
              .filter(column -> Set.of("STRING", "BOOLEAN").contains(Json.str(column, "dataType")))
              .limit(2)
              .toList();
      var measures =
          columns.stream()
              .filter(
                  column ->
                      NUMBERS.contains(Json.str(column, "dataType"))
                          && !keys.contains(Json.str(column, "name")))
              .limit(2)
              .toList();
      var times =
          columns.stream()
              .filter(column -> Set.of("DATE", "TIMESTAMP").contains(Json.str(column, "dataType")))
              .limit(1)
              .toList();
      for (var dimension : categories) {
        String field = Json.str(dimension, "name");
        add(
            output,
            "Phân bố số bản ghi theo " + field + " trong bảng " + name + "?",
            "Nhóm và đếm theo cột "
                + field
                + " có thật trong dataset; giá trị được truy vấn khi thực thi.",
            category,
            "PHYSICAL_SCHEMA",
            "bar",
            List.of(reference),
            List.of(),
            List.of(),
            engine);
      }
      for (var measure : measures) {
        String field = Json.str(measure, "name");
        add(
            output,
            "Tổng giá trị cột " + field + " trong bảng " + name + " là bao nhiêu?",
            "Phép tổng hợp trên cột số hiện tại. Ý nghĩa và đơn vị cần được quản trị viên cấu hình"
                + " trong ngữ nghĩa.",
            category,
            "PHYSICAL_SCHEMA",
            "number",
            List.of(reference),
            List.of(),
            List.of(),
            engine);
        for (var dimension : categories.stream().limit(1).toList()) {
          String group = Json.str(dimension, "name");
          add(
              output,
              "Tổng " + field + " theo " + group + " trong bảng " + name + "?",
              "Phân tích cùng một bảng; không suy đoán join, tiền tệ hoặc định nghĩa doanh thu.",
              category,
              "PHYSICAL_SCHEMA",
              "bar",
              List.of(reference),
              List.of(),
              List.of(),
              engine);
        }
        for (var time : times) {
          String date = Json.str(time, "name");
          add(
              output,
              "Xu hướng tổng " + field + " theo ngày của " + date + " trong bảng " + name + "?",
              "Cột thời gian và cột số lấy từ schema hiện tại; bộ sinh SQL vẫn phải kiểm tra"
                  + " dialect và ngữ nghĩa.",
              category,
              "PHYSICAL_SCHEMA",
              "line",
              List.of(reference),
              List.of(),
              List.of(),
              engine);
        }
      }
      for (var time : times) {
        add(
            output,
            "Số bản ghi theo ngày của " + Json.str(time, "name") + " trong bảng " + name + "?",
            "Phân bố theo cột DATE/TIMESTAMP thật của dataset.",
            category,
            "PHYSICAL_SCHEMA",
            "line",
            List.of(reference),
            List.of(),
            List.of(),
            engine);
      }
    }
    for (var metric : metrics) {
      String name = Json.str(metric, "name");
      String description = Json.str(metric, "description");
      String label = description.isBlank() ? name : description + " (" + name + ")";
      add(
          output,
          "Giá trị " + label + " là bao nhiêu?",
          "Metric được quản trị viên cấu hình trong ngữ nghĩa đang hoạt động.",
          "Ngữ nghĩa",
          "SEMANTIC_MODEL",
          "number",
          Json.objects(metric.get("tableRefs")),
          List.of(name),
          List.of(),
          engine);
      if (metric.get("compatibleDimensions") instanceof List<?> compatible) {
        for (var compatibleName : compatible.stream().limit(5).toList()) {
          var dimension = dimensionNames.get(Objects.toString(compatibleName));
          if (dimension == null) continue;
          List<Map<String, Object>> references =
              new ArrayList<>(Json.objects(metric.get("tableRefs")));
          var ref = Json.map(dimension.get("table"));
          if (!references.contains(ref)) references.add(ref);
          String type =
              Set.of("DATE", "TIMESTAMP").contains(Json.str(dimension, "dataType"))
                  ? "line"
                  : "bar";
          add(
              output,
              label + " theo " + Json.str(dimension, "name") + "?",
              "Dùng metric và dimension tương thích đã được cấu hình; giữ đường join và mức hạt đã"
                  + " khai báo.",
              "Ngữ nghĩa",
              "SEMANTIC_MODEL",
              type,
              references,
              List.of(name),
              List.of(Json.str(dimension, "name")),
              engine);
        }
      }
    }
    for (var example : Json.objects(context.get("examples"))) {
      if (!engine.equals(example.get("sqlDialect"))) continue;
      SqlGuard.Validated validated;
      try {
        validated = guard.validate(Json.str(example, "sql"), engine, tables);
      } catch (ApiException invalid) {
        continue;
      }
      var chart = Json.map(example.get("chartRecommendation"));
      String type = Json.str(chart, "type");
      if (!Set.of("number", "bar", "grouped-bar", "line", "area", "pie", "table").contains(type))
        type = "table";
      add(
          output,
          Json.str(example, "question"),
          "Ví dụ được quản trị viên cấu hình, dùng làm gợi ý và kiểm thử hồi quy; không phải câu"
              + " trả lời AI đã sinh trực tiếp.",
          "Ví dụ đã duyệt",
          "CURATED",
          type,
          validated.sourceTables(),
          strings(example.get("selectedMetrics")),
          strings(example.get("selectedDimensions")),
          engine);
    }
    return new ArrayList<>(output.values());
  }

  private static List<String> strings(Object value) {
    return value instanceof List<?> list
        ? list.stream().filter(String.class::isInstance).map(Object::toString).toList()
        : List.of();
  }

  private static void add(
      Map<String, Map<String, Object>> output,
      String question,
      String description,
      String category,
      String origin,
      String chart,
      List<Map<String, Object>> refs,
      List<String> metrics,
      List<String> dimensions,
      String engine) {
    if (question.isBlank() || question.codePointCount(0, question.length()) > 1000) return;
    String id = suggestionId(origin + "/" + engine + "/" + question);
    output.putIfAbsent(
        id,
        Json.obj(
            "suggestionId",
            id,
            "question",
            question,
            "description",
            description,
            "category",
            category,
            "origin",
            origin,
            "chartType",
            chart,
            "tableRefs",
            refs,
            "selectedMetrics",
            metrics,
            "selectedDimensions",
            dimensions,
            "sqlDialect",
            engine));
  }

  private static String suggestionId(String value) {
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable.", error);
    }
  }
}
