package com.fsa.textsql.access;

import com.fsa.textsql.common.ApiException;
import com.fsa.textsql.security.CurrentUser;
import com.fsa.textsql.warehouse.WarehouseRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Warehouse-level authorization: ADMIN may use every warehouse, other roles only the warehouses
 * granted to their token subject in warehouse_access.
 */
@Service
public class WarehouseAccessService {

  private final WarehouseAccessRepository grants;
  private final WarehouseRepository warehouses;

  public WarehouseAccessService(WarehouseAccessRepository grants, WarehouseRepository warehouses) {
    this.grants = grants;
    this.warehouses = warehouses;
  }

  /**
   * Throws 403 when the caller may not use the warehouse. Unknown IDs also give 403 for non-admin
   * callers so the response does not reveal which warehouses exist.
   */
  public void requireAccess(CurrentUser user, String warehouseId) {
    if (user.isAdmin()) {
      return;
    }
    if (!grants.hasAccess(user.subject(), warehouseId)) {
      throw ApiException.forbidden(
          "Không có quyền truy cập kho dữ liệu này.", Map.of("warehouseId", warehouseId));
    }
  }

  /** Subject used to restrict list queries, or null when the caller sees everything. */
  public String listScope(CurrentUser user) {
    return user.isAdmin() ? null : user.subject();
  }

  public List<String> accessibleWarehouseIds(CurrentUser user) {
    return user.isAdmin() ? warehouses.allIds() : grants.warehouseIdsFor(user.subject());
  }
}
