package com.fsa.textsql.catalog;

import java.util.List;

/**
 * Catalog view of one allowed table of a dataset. {@code tableId} is an opaque, stable id; {@code
 * status} is AVAILABLE, or MISSING when the table is allowed but absent from the latest scan.
 */
public record TableMetadata(
    String tableId,
    String schema,
    String name,
    String type,
    String description,
    String category,
    String status,
    boolean queryable,
    List<Column> columns,
    List<String> primaryKey,
    List<Relationship> foreignKeys) {

  public record Column(
      String name,
      String dataType,
      String nativeType,
      boolean nullable,
      String description,
      List<String> synonyms) {}
}
