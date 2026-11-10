package com.fsa.textsql.catalog;

import com.fsa.textsql.common.ApiException;
import com.fsa.textsql.common.PageQuery;
import com.fsa.textsql.common.PageResponse;
import com.fsa.textsql.security.CurrentUser;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Data Catalog endpoints of docs/contracts/public-api.md (section Catalog). */
@RestController
@RequestMapping("/api/v1/datasets/{datasetId}")
public class CatalogController {

  private static final Set<String> TABLE_FILTERS =
      Set.of("search", "schema", "category", "status", "queryable");

  private final CatalogService catalog;

  public CatalogController(CatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/catalog")
  public CatalogSummary summary(@PathVariable String datasetId) {
    return catalog.summary(datasetId, CurrentUser.require());
  }

  /**
   * Allowed tables of the dataset. {@code search} matches table or column names (case insensitive);
   * {@code schema}, {@code category}, {@code status}, {@code queryable} filter.
   */
  @GetMapping("/tables")
  public PageResponse<TableMetadata> tables(
      @PathVariable String datasetId, @RequestParam Map<String, String> params) {
    PageQuery query =
        PageQuery.parse(params, Map.of("name", "name", "schema", "schema"), "name", TABLE_FILTERS);
    CatalogService.View view = catalog.load(datasetId, CurrentUser.require());
    String search = lower(query.filters().get("search"));
    String schema = query.filters().get("schema");
    String category = query.filters().get("category");
    String status = query.filters().get("status");
    Boolean queryable = bool(query.filters().get("queryable"));
    List<TableMetadata> matching =
        view.tables().stream()
            .filter(t -> search == null || matches(t, search))
            .filter(t -> schema == null || schema.equals(t.schema()))
            .filter(t -> category == null || category.equals(t.category()))
            .filter(t -> status == null || status.equals(t.status()))
            .filter(t -> queryable == null || queryable == t.queryable())
            .toList();
    if (params.containsKey("sort")) {
      Comparator<TableMetadata> order =
          query.sortColumn().equals("schema")
              ? Comparator.comparing(TableMetadata::schema).thenComparing(TableMetadata::name)
              : Comparator.comparing((TableMetadata t) -> t.name().toLowerCase(Locale.ROOT));
      matching =
          matching.stream()
              .sorted(query.direction().equals("ASC") ? order : order.reversed())
              .toList();
    }
    return page(matching, query);
  }

  @GetMapping("/tables/{tableId}")
  public TableMetadata table(@PathVariable String datasetId, @PathVariable String tableId) {
    return catalog.load(datasetId, CurrentUser.require()).tables().stream()
        .filter(t -> t.tableId().equals(tableId))
        .findFirst()
        .orElseThrow(() -> ApiException.notFound("Không tìm thấy bảng trong tập dữ liệu."));
  }

  /** Relationships among the dataset's tables; {@code tableId} keeps links touching one table. */
  @GetMapping("/relationships")
  public PageResponse<Relationship> relationships(
      @PathVariable String datasetId, @RequestParam Map<String, String> params) {
    PageQuery query =
        PageQuery.parse(params, Map.of("name", "name"), "name", Set.of("search", "tableId"));
    CatalogService.View view = catalog.load(datasetId, CurrentUser.require());
    String tableId = query.filters().get("tableId");
    String search = lower(query.filters().get("search"));
    List<Relationship> matching =
        view.relationships().stream()
            .filter(
                r ->
                    tableId == null
                        || CatalogService.tableId(r.from().table()).equals(tableId)
                        || CatalogService.tableId(r.to().table()).equals(tableId))
            .filter(
                r ->
                    search == null
                        || (r.from().table().name() + " " + r.to().table().name())
                            .toLowerCase(Locale.ROOT)
                            .contains(search))
            .toList();
    return page(matching, query);
  }

  private static boolean matches(TableMetadata table, String search) {
    return table.name().toLowerCase(Locale.ROOT).contains(search)
        || table.columns().stream()
            .anyMatch(c -> c.name().toLowerCase(Locale.ROOT).contains(search));
  }

  private static <T> PageResponse<T> page(List<T> items, PageQuery query) {
    int from = Math.min(query.offset(), items.size());
    int to = Math.min(from + query.pageSize(), items.size());
    return PageResponse.of(items.subList(from, to), query, items.size());
  }

  private static Boolean bool(String value) {
    if (value == null) {
      return null;
    }
    if (!value.equals("true") && !value.equals("false")) {
      throw ApiException.invalidRequest(
          "queryable chỉ nhận true hoặc false.", Map.of("parameter", "queryable"));
    }
    return Boolean.valueOf(value);
  }

  private static String lower(String value) {
    return value == null ? null : value.toLowerCase(Locale.ROOT);
  }
}
