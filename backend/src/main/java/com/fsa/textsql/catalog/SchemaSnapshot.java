package com.fsa.textsql.catalog;

import com.fsa.textsql.dataset.TableRef;
import java.util.List;

/**
 * Physical metadata of a warehouse read by discovery: schemas, tables, columns, primary keys and
 * foreign keys. Contains names and types only, never data rows or credentials.
 */
public record SchemaSnapshot(List<String> schemas, List<Table> tables) {

  public SchemaSnapshot {
    schemas = List.copyOf(schemas);
    tables = List.copyOf(tables);
  }

  /** {@code type} is TABLE or VIEW. */
  public record Table(
      String schema,
      String name,
      String type,
      List<Column> columns,
      List<String> primaryKey,
      List<ForeignKey> foreignKeys) {

    public Table {
      columns = List.copyOf(columns);
      primaryKey = List.copyOf(primaryKey);
      foreignKeys = List.copyOf(foreignKeys);
    }

    public TableRef ref() {
      return new TableRef(schema, name);
    }
  }

  /** {@code dataType} is the canonical contract type; {@code nativeType} is the engine's name. */
  public record Column(String name, String dataType, String nativeType, boolean nullable) {}

  /** One referencing column; composite keys produce one entry per column, same constraint. */
  public record ForeignKey(String constraint, Endpoint from, Endpoint to) {}

  public record Endpoint(TableRef table, String column) {}
}
