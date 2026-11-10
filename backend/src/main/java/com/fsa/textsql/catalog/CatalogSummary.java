package com.fsa.textsql.catalog;

import java.util.List;

/**
 * GET /api/v1/datasets/{id}/catalog. Table and relationship counts come from the latest physical
 * scan; metric, dimension and example counts from the active semantic model.
 */
public record CatalogSummary(
    String datasetId,
    String semanticVersion,
    String schemaVersion,
    int tableCount,
    int metricCount,
    int dimensionCount,
    int relationshipCount,
    int exampleCount,
    List<Category> tableCategories,
    List<Category> metricCategories,
    List<Category> exampleCategories) {

  public record Category(String name, long count) {}
}
