package dev.nla.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nla.common.Json;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** T-4.3.2, US-4.3 AC2: the AI chart suggestion is checked against the real result. */
class ChartSpecsTest {
  private static Map<String, Object> col(String name, String type) {
    return Json.obj("name", name, "label", name, "dataType", type, "semanticRole", "OTHER");
  }

  /** country (STRING) and revenue (DECIMAL as string) over three rows. */
  private static Map<String, Object> byCountry() {
    return Json.obj(
        "columns",
        List.of(col("country", "STRING"), col("revenue", "DECIMAL"), col("orders", "INTEGER")),
        "rows",
        List.of(
            Json.obj("country", "VN", "revenue", "120.50", "orders", 3),
            Json.obj("country", "US", "revenue", "80.00", "orders", 2),
            Json.obj("country", "FR", "revenue", null, "orders", 1)));
  }

  private static Map<String, Object> rec(String type, String x, String... series) {
    return Json.obj(
        "type",
        type,
        "title",
        "Doanh thu",
        "x",
        x,
        "series",
        Arrays.stream(series).map(s -> Json.obj("column", s, "label", "Nhãn " + s)).toList());
  }

  @Test
  void keepsAValidBarChartBuiltOnlyFromAllowedFields() {
    var spec = rec("bar", "country", "revenue");
    spec.put("onClick", "alert(1)");
    var chart = ChartSpecs.decide(spec, byCountry());
    assertThat(chart)
        .containsExactly(
            Map.entry("type", "bar"),
            Map.entry("title", "Doanh thu"),
            Map.entry("x", "country"),
            Map.entry("series", List.of(Json.obj("column", "revenue", "label", "Nhãn revenue"))));
  }

  @Test
  void matchesAliasesCaseInsensitivelyAndReturnsTheRealColumnName() {
    var chart = ChartSpecs.decide(rec("line", "COUNTRY", "Revenue"), byCountry());
    assertThat(chart.get("type")).isEqualTo("line");
    assertThat(chart.get("x")).isEqualTo("country");
    assertThat(Json.objects(chart.get("series")).get(0).get("column")).isEqualTo("revenue");
  }

  @Test
  void fallsBackToTableWhenAColumnIsMissing() {
    var chart = ChartSpecs.decide(rec("bar", "region", "revenue"), byCountry());
    assertThat(chart.get("type")).isEqualTo("table");
    assertThat(chart.get("series")).isEqualTo(List.of());
    assertThat(chart).doesNotContainKey("x");
    assertThat((String) chart.get("reason")).contains("region");

    chart = ChartSpecs.decide(rec("bar", "country", "profit"), byCountry());
    assertThat(chart.get("type")).isEqualTo("table");
    assertThat((String) chart.get("reason")).contains("profit");
  }

  @Test
  void fallsBackToTableWhenTheSeriesIsNotNumeric() {
    var chart = ChartSpecs.decide(rec("bar", "revenue", "country"), byCountry());
    assertThat(chart.get("type")).isEqualTo("table");
    assertThat((String) chart.get("reason")).contains("country").contains("STRING");
  }

  @Test
  void rejectsShapesThatDoNotFitTheType() {
    assertThat(ChartSpecs.decide(rec("bar", "country", "revenue", "orders"), byCountry()))
        .containsEntry("type", "table");
    assertThat(ChartSpecs.decide(rec("grouped-bar", "country", "revenue"), byCountry()))
        .containsEntry("type", "table");
    assertThat(ChartSpecs.decide(rec("grouped-bar", "country", "revenue", "orders"), byCountry()))
        .containsEntry("type", "grouped-bar");
    assertThat(ChartSpecs.decide(rec("line", null, "revenue"), byCountry()))
        .containsEntry("type", "table");
    assertThat(ChartSpecs.decide(rec("bar", "country", "revenue", "revenue"), byCountry()))
        .containsEntry("type", "table");
    assertThat(ChartSpecs.decide(rec("number", null, "orders"), byCountry()))
        .containsEntry("type", "table");
    assertThat(ChartSpecs.decide(rec("bar", "country"), byCountry()))
        .containsEntry("type", "table");
  }

  @Test
  void rejectsUnknownTypesAndMissingRecommendations() {
    var chart = ChartSpecs.decide(rec("scatter", "country", "revenue"), byCountry());
    assertThat(chart.get("type")).isEqualTo("table");
    assertThat((String) chart.get("reason")).contains("scatter");

    chart = ChartSpecs.decide(null, byCountry());
    assertThat(chart.get("type")).isEqualTo("table");
    assertThat(chart.get("title")).isEqualTo(ChartSpecs.DEFAULT_TITLE);
    assertThat(chart).containsKey("reason");

    chart = ChartSpecs.decide(rec("table", null), byCountry());
    assertThat(chart).containsEntry("type", "table").doesNotContainKey("reason");
  }

  @Test
  void pieNeedsNonNegativeValuesWithAPositiveTotal() {
    assertThat(ChartSpecs.decide(rec("pie", "country", "revenue"), byCountry()))
        .containsEntry("type", "pie");
    var negative =
        Json.obj(
            "columns",
            List.of(col("country", "STRING"), col("profit", "FLOAT")),
            "rows",
            List.of(
                Json.obj("country", "VN", "profit", 5.0),
                Json.obj("country", "US", "profit", -1.5)));
    assertThat(ChartSpecs.decide(rec("pie", "country", "profit"), negative))
        .containsEntry("type", "table");
    var zero =
        Json.obj(
            "columns",
            List.of(col("country", "STRING"), col("profit", "BIGINT")),
            "rows",
            List.of(Json.obj("country", "VN", "profit", "0")));
    assertThat(ChartSpecs.decide(rec("pie", "country", "profit"), zero))
        .containsEntry("type", "table");
  }

  @Test
  void aSingleNumberBecomesAKpiCardWhateverWasSuggested() {
    var scalar =
        Json.obj(
            "columns", List.of(col("total", "BIGINT")), "rows", List.of(Json.obj("total", "412")));
    var chart = ChartSpecs.decide(rec("bar", "country", "revenue"), scalar);
    assertThat(chart.get("type")).isEqualTo("number");
    assertThat(chart).doesNotContainKey("x");
    assertThat(chart.get("series"))
        .isEqualTo(List.of(Json.obj("column", "total", "label", "total")));
    assertThat(ChartSpecs.decide(null, scalar)).containsEntry("type", "number");
  }

  @Test
  void emptyOrAllNullResultsAreShownAsTable() {
    var empty = Json.obj("columns", byCountry().get("columns"), "rows", List.of());
    assertThat((String) ChartSpecs.decide(rec("bar", "country", "revenue"), empty).get("reason"))
        .contains("Không có dòng");
    var nulls =
        Json.obj(
            "columns",
            List.of(col("country", "STRING"), col("revenue", "DECIMAL")),
            "rows",
            List.of(Json.obj("country", "VN", "revenue", null)));
    assertThat(ChartSpecs.decide(rec("bar", "country", "revenue"), nulls))
        .containsEntry("type", "table");
  }

  @Test
  void replacesOverlongOrBlankTitlesAndLabels() {
    var spec = rec("bar", "country", "revenue");
    spec.put("title", "x".repeat(ChartSpecs.MAX_TITLE + 1));
    spec.put("series", List.of(Json.obj("column", "revenue", "label", " ")));
    var chart = ChartSpecs.decide(spec, byCountry());
    assertThat(chart.get("title")).isEqualTo(ChartSpecs.DEFAULT_TITLE);
    assertThat(Json.objects(chart.get("series")).get(0).get("label")).isEqualTo("revenue");
  }
}
