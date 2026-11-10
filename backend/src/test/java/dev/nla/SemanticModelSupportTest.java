package dev.nla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticModelSupportTest {
  @Test
  void refusesSemanticDefinitionFromAnotherPhysicalSchemaVersion() {
    var store = mock(Store.class);
    var catalog = mock(CatalogService.class);
    var document = document("schema-v2");
    when(store.dataset("dataset"))
        .thenReturn(Json.obj("datasetId", "dataset", "schemaVersion", "schema-v1"));

    var error =
        assertThrows(
            ApiException.class,
            () ->
                SemanticModelSupport.validateDocument(
                    "dataset", document, store, catalog, new SqlGuard()));

    assertEquals("SCHEMA_MISMATCH", error.code);
    verify(catalog, never()).tables("dataset");
  }

  @Test
  void validatesAgainstCurrentPhysicalTablesAndWarehouseDialect() {
    var store = mock(Store.class);
    var catalog = mock(CatalogService.class);
    var tables =
        List.of(
            Json.obj(
                "schema",
                "public",
                "name",
                "items",
                "columns",
                List.of(Json.obj("name", "id", "dataType", "INTEGER")),
                "primaryKey",
                List.of("id")));
    when(store.dataset("dataset"))
        .thenReturn(
            Json.obj(
                "datasetId", "dataset", "warehouseId", "warehouse", "schemaVersion", "schema-v1"));
    when(store.warehouse("warehouse"))
        .thenReturn(Json.obj("warehouseId", "warehouse", "engine", "POSTGRESQL"));
    when(catalog.tables("dataset")).thenReturn(tables);
    var document = document("schema-v1");

    SemanticModelSupport.validateDocument("dataset", document, store, catalog, new SqlGuard());

    verify(catalog)
        .validateSemantic(
            org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.eq(tables));
    verify(catalog)
        .validateJoinCardinality(
            org.mockito.ArgumentMatchers.eq("dataset"), org.mockito.ArgumentMatchers.anyMap());
  }

  private static java.util.Map<String, Object> document(String schemaVersion) {
    return Json.obj(
        "tables",
        List.of(),
        "metrics",
        List.of(),
        "dimensions",
        List.of(),
        "relationships",
        List.of(),
        "examples",
        List.of(),
        "businessRules",
        List.of(),
        "semanticVersion",
        "semantic-v1",
        "schemaVersion",
        schemaVersion,
        "timezone",
        "UTC",
        "referenceTime",
        "2026-01-01T00:00:00Z");
  }
}
