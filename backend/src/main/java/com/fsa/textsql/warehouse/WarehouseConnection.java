package com.fsa.textsql.warehouse;

/**
 * Internal connection settings of a warehouse. Never serialized to API responses; the password is
 * not here either, only the server-side credential reference.
 */
public record WarehouseConnection(
    String id,
    String name,
    Engine engine,
    WarehouseStatus status,
    String host,
    int port,
    String database,
    String defaultSchema,
    String username,
    String credentialReference) {

  @Override
  public String toString() {
    // Keep connection details out of logs and exception messages.
    return "WarehouseConnection[id=" + id + ", engine=" + engine + "]";
  }
}
