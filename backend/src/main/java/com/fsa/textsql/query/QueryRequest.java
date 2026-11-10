package com.fsa.textsql.query;

/**
 * Body of POST /api/v1/query (docs/contracts/domain.md). Unknown fields are rejected by the
 * controller so clients cannot smuggle SQL, context or credentials.
 */
public record QueryRequest(
    String warehouseId, String datasetId, String question, String mode, String providerId) {}
