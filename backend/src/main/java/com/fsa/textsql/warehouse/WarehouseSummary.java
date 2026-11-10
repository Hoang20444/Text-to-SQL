package com.fsa.textsql.warehouse;

/**
 * Safe list/selector view of a warehouse. Connection fields and the credential reference are
 * deliberately absent so they can never reach the browser through this DTO.
 */
public record WarehouseSummary(
    String warehouseId,
    String name,
    Engine engine,
    WarehouseStatus status,
    Capabilities capabilities) {

  public record Capabilities(boolean readOnlyQuery, boolean metadataDiscovery) {}
}
