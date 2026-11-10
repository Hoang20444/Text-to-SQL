package dev.nla;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DatasetSuggestionTest {
  static Map<String, Object> context(String schema, String table) {
    return Json.obj(
        "tables",
        List.of(
            Json.obj(
                "schema",
                schema,
                "name",
                table,
                "primaryKey",
                List.of("id"),
                "columns",
                List.of(
                    Json.obj("name", "id", "dataType", "BIGINT"),
                    Json.obj("name", "region_code", "dataType", "STRING"),
                    Json.obj("name", "measurement", "dataType", "DECIMAL"),
                    Json.obj("name", "observed_at", "dataType", "TIMESTAMP")))),
        "metrics",
        List.of(),
        "dimensions",
        List.of(),
        "examples",
        List.of(),
        "businessRules",
        List.of(),
        "relationships",
        List.of(),
        "semanticVersion",
        "active-s",
        "schemaVersion",
        "active-p",
        "timezone",
        "UTC");
  }

  @ParameterizedTest
  @ValueSource(strings = {"POSTGRESQL", "MYSQL", "SQL_SERVER"})
  void newDatasetWithEmptyBusinessSemanticsGetsItsOwnPhysicalQuestions(String engine) {
    var first =
        DatasetSuggestionService.derive(
            context("climate", "station_events"), engine, new SqlGuard());
    var second =
        DatasetSuggestionService.derive(
            context("logistics", "delivery_events"), engine, new SqlGuard());
    assertFalse(first.isEmpty());
    assertTrue(first.stream().allMatch(item -> Json.str(item, "origin").equals("PHYSICAL_SCHEMA")));
    assertTrue(first.stream().allMatch(item -> item.get("sqlDialect").equals(engine)));
    assertTrue(Json.write(first).contains("climate.station_events"));
    assertFalse(Json.write(second).contains("station_events"));
    assertFalse(Json.write(first).toLowerCase(Locale.ROOT).contains("chinook"));
    assertTrue(first.stream().anyMatch(item -> "line".equals(item.get("chartType"))));
    assertTrue(first.stream().anyMatch(item -> "bar".equals(item.get("chartType"))));
    assertFalse(
        first.stream()
            .anyMatch(item -> Json.str(item, "question").startsWith("Tổng giá trị cột id")));
    assertTrue(first.stream().allMatch(item -> !item.containsKey("sql")));
  }

  @Test
  void semanticQuestionsUseOnlyDeclaredCompatibleDimensionsAndCuratedExamplesAreLabeled() {
    var context = context("analytics", "events");
    var ref = Json.obj("schema", "analytics", "name", "events");
    context.put(
        "dimensions",
        List.of(
            Json.obj("name", "region", "table", ref, "column", "region_code", "dataType", "STRING"),
            Json.obj(
                "name", "unused", "table", ref, "column", "region_code", "dataType", "STRING")));
    context.put(
        "metrics",
        List.of(
            Json.obj(
                "name",
                "amount_sum",
                "description",
                "Configured measurement sum",
                "tableRefs",
                List.of(ref),
                "compatibleDimensions",
                List.of("region"))));
    context.put(
        "examples",
        List.of(
            Json.obj(
                "question",
                "Approved measurement comparison?",
                "sqlDialect",
                "POSTGRESQL",
                "sql",
                "SELECT region_code, SUM(measurement) AS total, AVG(measurement) AS mean"
                    + " FROM analytics.events GROUP BY region_code",
                "chartRecommendation",
                Json.obj("type", "grouped-bar")),
            Json.obj(
                "question",
                "Unrelated invalid example?",
                "sqlDialect",
                "POSTGRESQL",
                "sql",
                "SELECT * FROM private.payroll")));
    var suggestions = DatasetSuggestionService.derive(context, "POSTGRESQL", new SqlGuard());
    assertTrue(
        suggestions.stream()
            .anyMatch(
                item ->
                    "SEMANTIC_MODEL".equals(item.get("origin"))
                        && List.of("region").equals(item.get("selectedDimensions"))));
    assertFalse(
        suggestions.stream()
            .anyMatch(item -> List.of("unused").equals(item.get("selectedDimensions"))));
    assertEquals(
        1, suggestions.stream().filter(item -> "CURATED".equals(item.get("origin"))).count());
    assertEquals(
        "grouped-bar",
        suggestions.stream()
            .filter(item -> "CURATED".equals(item.get("origin")))
            .findFirst()
            .orElseThrow()
            .get("chartType"));
  }

  @Test
  void deniedResourceCannotReachMetadata() {
    Store store = mock(Store.class);
    CatalogService catalog = mock(CatalogService.class);
    when(store.dataset("denied"))
        .thenThrow(
            new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Denied"));
    var service = new DatasetSuggestionService(store, catalog, new SqlGuard());
    assertThrows(ApiException.class, () -> service.suggestions("denied", Map.of()));
    verifyNoInteractions(catalog);
  }
}
