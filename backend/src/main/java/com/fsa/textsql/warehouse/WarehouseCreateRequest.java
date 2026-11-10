package com.fsa.textsql.warehouse;

/**
 * Body of POST /api/v1/warehouses (ADMIN). {@code credentialReference} names a server-side secret
 * ({@code env:NAME}); the password itself is never sent to or stored by the API.
 */
public record WarehouseCreateRequest(
    String name,
    String engine,
    String host,
    Integer port,
    String database,
    String defaultSchema,
    String username,
    String credentialReference) {}
