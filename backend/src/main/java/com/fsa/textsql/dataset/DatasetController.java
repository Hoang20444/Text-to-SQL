package com.fsa.textsql.dataset;

import com.fsa.textsql.access.WarehouseAccessService;
import com.fsa.textsql.common.PageQuery;
import com.fsa.textsql.common.PageResponse;
import com.fsa.textsql.security.CurrentUser;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/datasets")
public class DatasetController {

  private static final Map<String, String> SORT_COLUMNS =
      Map.of(
          "createdAt", "created_at",
          "updatedAt", "updated_at",
          "name", "name",
          "status", "status");
  private static final Set<String> FILTERS = Set.of("warehouseId", "status", "search");

  private final DatasetRepository datasets;
  private final WarehouseAccessService access;
  private final DatasetService service;

  public DatasetController(
      DatasetRepository datasets, WarehouseAccessService access, DatasetService service) {
    this.datasets = datasets;
    this.access = access;
    this.service = service;
  }

  /** POST /api/v1/datasets (ADMIN): tables must exist in the latest scan; 201 + Location. */
  @org.springframework.web.bind.annotation.PostMapping
  public org.springframework.http.ResponseEntity<Dataset> create(
      @org.springframework.web.bind.annotation.RequestBody DatasetCreateRequest request) {
    Dataset created = service.create(request, CurrentUser.require());
    return org.springframework.http.ResponseEntity.created(
            java.net.URI.create("/api/v1/datasets/" + created.datasetId()))
        .body(created);
  }

  /**
   * GET /api/v1/datasets: Page of datasets; filters warehouseId, status, search. Asking for a
   * warehouse the caller may not access gives 403; without the filter only accessible warehouses
   * are listed.
   */
  @GetMapping
  public PageResponse<Dataset> list(@RequestParam Map<String, String> params) {
    PageQuery query = PageQuery.parse(params, SORT_COLUMNS, "createdAt", FILTERS);
    query.requireEnum("status", DatasetStatus.class);
    CurrentUser user = CurrentUser.require();
    String warehouseId = query.filters().get("warehouseId");
    if (warehouseId != null) {
      access.requireAccess(user, warehouseId);
    }
    return datasets.findPage(query, access.listScope(user));
  }
}
