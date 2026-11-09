package com.fsa.textsql.warehouse;

import java.util.Objects;

/** Server-side input used to register a warehouse. Never serialized to API responses. */
public record WarehouseRegistration(
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

  public WarehouseRegistration {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(engine, "engine");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(username, "username");
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("port must be between 1 and 65535");
    }
    if (credentialReference == null || !credentialReference.startsWith("env:")) {
      throw new IllegalArgumentException("credentialReference must have the form env:NAME");
    }
  }
}
