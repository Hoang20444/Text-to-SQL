package com.fsa.textsql.catalog;

import com.fsa.textsql.common.ErrorResponse;
import java.time.Instant;

/**
 * Discovery run (docs/contracts/public-api.md): PENDING, RUNNING, SUCCESS or FAILED. {@code
 * metadata} is the physical snapshot when SUCCESS, otherwise null.
 */
public record DiscoveryRun(
    String runId,
    String warehouseId,
    String status,
    Instant createdAt,
    Instant completedAt,
    String schemaVersion,
    Integer tableCount,
    SchemaSnapshot metadata,
    ErrorResponse.ErrorBody error) {}
