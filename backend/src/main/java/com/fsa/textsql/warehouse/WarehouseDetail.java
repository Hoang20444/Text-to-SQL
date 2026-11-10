package com.fsa.textsql.warehouse;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Warehouse detail. Connection fields are filled only for ADMIN callers (null and omitted for
 * others); the credential reference is never part of any response.
 */
public record WarehouseDetail(
    String warehouseId,
    String name,
    Engine engine,
    WarehouseStatus status,
    @JsonInclude(JsonInclude.Include.NON_NULL) String host,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer port,
    @JsonInclude(JsonInclude.Include.NON_NULL) String database,
    @JsonInclude(JsonInclude.Include.NON_NULL) String defaultSchema,
    @JsonInclude(JsonInclude.Include.NON_NULL) String username,
    WarehouseSummary.Capabilities capabilities,
    String schemaVersion,
    Instant lastDiscoveredAt,
    Instant createdAt,
    Instant updatedAt) {

  /** Same warehouse without connection settings, for non-admin callers. */
  public WarehouseDetail withoutConnection() {
    return new WarehouseDetail(
        warehouseId,
        name,
        engine,
        status,
        null,
        null,
        null,
        null,
        null,
        capabilities,
        schemaVersion,
        lastDiscoveredAt,
        createdAt,
        updatedAt);
  }
}
