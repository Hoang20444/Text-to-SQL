package com.fsa.textsql.dataset;

import java.time.Instant;
import java.util.List;

/** Public Dataset DTO (docs/contracts/domain.md). */
public record Dataset(
    String datasetId,
    String warehouseId,
    String name,
    String description,
    DatasetStatus status,
    List<String> allowedSchemas,
    List<TableRef> allowedTables,
    String semanticVersion,
    String schemaVersion,
    Instant updatedAt) {}
