package com.fsa.textsql.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsa.textsql.access.WarehouseAccessService;
import com.fsa.textsql.common.ApiException;
import com.fsa.textsql.common.PageQuery;
import com.fsa.textsql.common.PageResponse;
import com.fsa.textsql.dataset.Dataset;
import com.fsa.textsql.dataset.DatasetRepository;
import com.fsa.textsql.security.CurrentUser;
import com.fsa.textsql.warehouse.WarehouseConnection;
import com.fsa.textsql.warehouse.WarehouseRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/datasets/{id}/examples: suggested sample questions of the selected dataset, in the
 * dialect of its warehouse (US-1.3 AC1: suggestions change with the dataset).
 */
@RestController
public class ExampleController {

  private static final Set<String> FILTERS = Set.of("search", "category", "language");

  private final DatasetRepository datasets;
  private final WarehouseRepository warehouses;
  private final WarehouseAccessService access;
  private final ObjectMapper json;

  public ExampleController(
      DatasetRepository datasets,
      WarehouseRepository warehouses,
      WarehouseAccessService access,
      ObjectMapper json) {
    this.datasets = datasets;
    this.warehouses = warehouses;
    this.access = access;
    this.json = json;
  }

  public record Example(
      String question,
      String sqlDialect,
      String sql,
      List<String> selectedMetrics,
      List<String> selectedDimensions,
      String category,
      String language) {}

  @GetMapping("/api/v1/datasets/{datasetId}/examples")
  public PageResponse<Example> list(
      @PathVariable String datasetId, @RequestParam Map<String, String> params) {
    PageQuery query = PageQuery.parse(params, Map.of("question", "question"), "question", FILTERS);
    CurrentUser user = CurrentUser.require();
    Dataset dataset = datasets.findById(datasetId).orElse(null);
    if (dataset == null) {
      if (!user.isAdmin()) {
        // Same answer as a non-granted dataset: do not reveal which IDs exist.
        throw ApiException.forbidden("Không có quyền truy cập tập dữ liệu này.", Map.of());
      }
      throw ApiException.notFound("Không tìm thấy tập dữ liệu.");
    }
    access.requireAccess(user, dataset.warehouseId());
    WarehouseConnection warehouse =
        warehouses
            .findConnection(dataset.warehouseId())
            .orElseThrow(() -> ApiException.notFound("Không tìm thấy kho dữ liệu."));
    SemanticModel semantic =
        SemanticModel.parse(datasets.semanticModel(datasetId).orElse("{}"), json);

    String search = lower(query.filters().get("search"));
    String category = query.filters().get("category");
    String language = query.filters().get("language");
    List<Example> all =
        semantic.examplesFor(warehouse.engine()).stream()
            .map(ExampleController::toExample)
            .filter(e -> search == null || lower(e.question()).contains(search))
            .filter(e -> category == null || category.equals(e.category()))
            .filter(e -> language == null || language.equals(e.language()))
            .toList();
    if (params.containsKey("sort")) {
      Comparator<Example> byQuestion = Comparator.comparing(e -> lower(e.question()));
      all =
          all.stream()
              .sorted(query.direction().equals("ASC") ? byQuestion : byQuestion.reversed())
              .toList();
    }
    int from = Math.min(query.offset(), all.size());
    int to = Math.min(from + query.pageSize(), all.size());
    return PageResponse.of(all.subList(from, to), query, all.size());
  }

  @SuppressWarnings("unchecked")
  private static Example toExample(Map<String, Object> e) {
    return new Example(
        (String) e.get("question"),
        (String) e.get("sqlDialect"),
        (String) e.get("sql"),
        (List<String>) e.getOrDefault("selectedMetrics", List.of()),
        (List<String>) e.getOrDefault("selectedDimensions", List.of()),
        (String) e.get("category"),
        (String) e.get("language"));
  }

  private static String lower(String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }
}
