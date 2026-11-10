package com.fsa.textsql.warehouse;

import com.fsa.textsql.access.WarehouseAccessService;
import com.fsa.textsql.common.PageQuery;
import com.fsa.textsql.common.PageResponse;
import com.fsa.textsql.security.CurrentUser;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/warehouses")
public class WarehouseController {

  private static final Map<String, String> SORT_COLUMNS =
      Map.of(
          "createdAt", "created_at",
          "name", "name",
          "engine", "engine",
          "status", "status");
  private static final Set<String> FILTERS = Set.of("engine", "status", "search");

  private final WarehouseRepository warehouses;
  private final WarehouseAccessService access;
  private final WarehouseService service;

  public WarehouseController(
      WarehouseRepository warehouses, WarehouseAccessService access, WarehouseService service) {
    this.warehouses = warehouses;
    this.access = access;
    this.service = service;
  }

  /**
   * GET /api/v1/warehouses: Page of safe summaries; filters engine, status, search. Only the
   * warehouses the caller may access are listed (ADMIN sees all).
   */
  @GetMapping
  public PageResponse<WarehouseSummary> list(@RequestParam Map<String, String> params) {
    PageQuery query = PageQuery.parse(params, SORT_COLUMNS, "createdAt", FILTERS);
    query.requireEnum("engine", Engine.class);
    query.requireEnum("status", WarehouseStatus.class);
    return warehouses.findPage(query, access.listScope(CurrentUser.require()));
  }

  /** POST /api/v1/warehouses (ADMIN): 201 + Location; status stays UNAVAILABLE until tested. */
  @PostMapping
  public ResponseEntity<WarehouseDetail> register(@RequestBody WarehouseCreateRequest request) {
    WarehouseDetail created = service.register(request, CurrentUser.require());
    return ResponseEntity.created(URI.create("/api/v1/warehouses/" + created.warehouseId()))
        .body(created);
  }

  /** POST /api/v1/warehouses/{id}/test (ADMIN): reachable + latency, or a sanitized reason. */
  @PostMapping("/{warehouseId}/test")
  public ConnectionTestResult test(@PathVariable String warehouseId) {
    return service.testConnection(warehouseId, CurrentUser.require());
  }

  /** GET /api/v1/warehouses/{id}: authorized detail; connection fields only for ADMIN. */
  @GetMapping("/{warehouseId}")
  public WarehouseDetail detail(@PathVariable String warehouseId) {
    return service.detail(warehouseId, CurrentUser.require());
  }
}
