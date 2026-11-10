package com.fsa.textsql.warehouse;

import com.fsa.textsql.common.ErrorResponse;
import java.time.Instant;

/**
 * Result of POST /api/v1/warehouses/{id}/test. A failed check is a business result (reachable=false
 * with a sanitized error), not an HTTP error.
 */
public record ConnectionTestResult(
    String warehouseId,
    boolean reachable,
    Long latencyMs,
    Instant checkedAt,
    ErrorResponse.ErrorBody error) {}
