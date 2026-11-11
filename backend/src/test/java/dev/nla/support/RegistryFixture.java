package dev.nla.support;

import dev.nla.common.Json;
import dev.nla.store.Store;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-only registry rows for the three engines, written through the real metadata schema. Live
 * Chinook discovery is covered by the warehouse integration tests.
 */
public final class RegistryFixture {
  private RegistryFixture() {}

  public static void reset(JdbcTemplate jdbc) {
    jdbc.update("DELETE FROM resource_access");
    jdbc.update("DELETE FROM query_history");
    jdbc.update("DELETE FROM dataset");
    jdbc.update("DELETE FROM warehouse");
    var store = new Store(jdbc);
    warehouse(store, "pg", "POSTGRESQL", "Chinook (PostgreSQL)", "public", "album");
    warehouse(store, "mysql", "MYSQL", "Chinook (MySQL)", "nla_chinook", "Album");
    warehouse(store, "mssql", "SQL_SERVER", "Chinook (SQL Server)", "dbo", "Album");
    for (String suffix : List.of("pg", "mysql", "mssql"))
      store.grantWarehouseAccess("wh-chinook-" + suffix, TestJwt.ANALYST_SUB);
    store.grantWarehouseAccess("wh-chinook-pg", TestJwt.VIEWER_SUB);
  }

  private static void warehouse(
      Store store, String suffix, String engine, String name, String schema, String table) {
    String now = "2026-11-11T00:00:00Z";
    Map<String, Object> w =
        Json.obj(
            "warehouseId",
            "wh-chinook-" + suffix,
            "name",
            name,
            "engine",
            engine,
            "host",
            "127.0.0.1",
            "port",
            5432,
            "database",
            "nla_chinook",
            "defaultSchema",
            schema,
            "username",
            "nla_reader",
            "status",
            "ACTIVE",
            "capabilities",
            Json.obj("readOnlyQuery", true, "metadataDiscovery", true),
            "createdAt",
            now,
            "updatedAt",
            now);
    store.insertWarehouse(w, "env:NLA_TEST_" + suffix.toUpperCase() + "_PASSWORD");
    store.insertDataset(
        Json.obj(
            "datasetId",
            "ds-chinook-" + suffix,
            "warehouseId",
            "wh-chinook-" + suffix,
            "name",
            "nla_chinook",
            "description",
            "Chinook on " + engine,
            "allowedSchemas",
            List.of(schema),
            "allowedTables",
            List.of(Json.obj("schema", schema, "name", table)),
            "semanticVersion",
            "1.0.0",
            "schemaVersion",
            "chinook-1.4.5",
            "status",
            "ACTIVE",
            "createdAt",
            now,
            "updatedAt",
            now),
        Json.obj("metrics", List.of(), "dimensions", List.of(), "examples", List.of()));
  }
}
