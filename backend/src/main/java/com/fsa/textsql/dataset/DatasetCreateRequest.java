package com.fsa.textsql.dataset;

import java.util.List;

/**
 * Body of POST /api/v1/datasets (ADMIN). Versions are not accepted from the client: the server sets
 * schemaVersion from the latest scan and semanticVersion until a semantic model exists.
 */
public record DatasetCreateRequest(
    String warehouseId,
    String name,
    String description,
    List<String> allowedSchemas,
    List<TableRef> allowedTables) {}
