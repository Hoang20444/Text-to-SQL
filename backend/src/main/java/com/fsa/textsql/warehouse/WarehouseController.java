package com.fsa.textsql.warehouse;

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

  public WarehouseController(WarehouseRepository warehouses, WarehouseAccessService access) {
    this.warehouses = warehouses;
    this.access = access;
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
}
