package com.fsa.textsql.dataset;

import java.util.List;
import java.util.Objects;

/** Server-side input used to register a dataset on an existing warehouse. */
public record DatasetRegistration(
    String id,
    String warehouseId,
    String name,
    String description,
    DatasetStatus status,
    List<String> allowedSchemas,
    List<TableRef> allowedTables,
    String semanticVersion,
    String schemaVersion) {

  public DatasetRegistration {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(warehouseId, "warehouseId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(semanticVersion, "semanticVersion");
    Objects.requireNonNull(schemaVersion, "schemaVersion");
    description = description == null ? "" : description;
    allowedSchemas = List.copyOf(allowedSchemas);
    allowedTables = List.copyOf(allowedTables);
    // Security allowlists must be qualified: an unqualified table name is ambiguous.
    for (TableRef table : allowedTables) {
      if (table.schema() == null
          || table.schema().isBlank()
          || table.name() == null
          || table.name().isBlank()) {
        throw new IllegalArgumentException("allowedTables entries need both schema and name");
      }
      if (!allowedSchemas.contains(table.schema())) {
        throw new IllegalArgumentException(
            "table " + table.schema() + "." + table.name() + " is outside allowedSchemas");
      }
    }
  }
}
