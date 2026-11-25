package dev.nla.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nla.common.Json;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** T-4.3.2, US-4.3 AC3: CSV export is UTF-8 for Excel and cannot carry formulas. */
class CsvExportTest {
  private static Map<String, Object> col(String name, String type) {
    return Json.obj("name", name, "label", name, "dataType", type, "semanticRole", "OTHER");
  }

  private static String csv(List<Map<String, Object>> columns, List<Map<String, Object>> rows) {
    byte[] bytes = CsvExport.write(Json.obj("columns", columns, "rows", rows));
    assertThat(Arrays.copyOf(bytes, 3)).isEqualTo(CsvExport.BOM);
    return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
  }

  @Test
  void writesBomHeaderAndCrlfRows() {
    String text =
        csv(
            List.of(col("quoc_gia", "STRING"), col("doanh_thu", "DECIMAL")),
            List.of(Json.obj("quoc_gia", "Việt Nam", "doanh_thu", "130.25")));
    assertThat(text).isEqualTo("\"quoc_gia\",\"doanh_thu\"\r\n\"Việt Nam\",\"130.25\"\r\n");
  }

  @Test
  void neutralisesFormulaCellsInTextColumns() {
    var columns = List.of(col("note", "STRING"));
    for (String formula :
        List.of(
            "=1+1",
            "+SUM(A1:A2)",
            "-2+3",
            "@cmd",
            " =HYPERLINK(\"http://x\")",
            "\n=1",
            "\tTAB",
            "\rCR")) {
      String text = csv(columns, List.of(Json.obj("note", formula)));
      String cell = text.substring(text.indexOf("\r\n") + 2);
      assertThat(cell).as(formula).startsWith("\"'");
    }
  }

  @Test
  void leavesNormalTextAndNumbersUntouched() {
    String text =
        csv(
            List.of(
                col("name", "STRING"),
                col("profit", "DECIMAL"),
                col("count", "INTEGER"),
                col("ratio", "FLOAT"),
                col("paid", "BOOLEAN"),
                col("day", "DATE")),
            List.of(
                Json.obj(
                    "name",
                    "a=b",
                    "profit",
                    "-12.50",
                    "count",
                    -3,
                    "ratio",
                    -0.5,
                    "paid",
                    true,
                    "day",
                    "2026-01-01")));
    assertThat(text.split("\r\n")[1])
        .isEqualTo("\"a=b\",\"-12.50\",\"-3\",\"-0.5\",\"true\",\"2026-01-01\"");
  }

  @Test
  void escapesQuotesAndKeepsNullDifferentFromEmptyText() {
    String text =
        csv(
            List.of(col("a", "STRING"), col("b", "STRING"), col("c", "DECIMAL")),
            List.of(Json.obj("a", "say \"hi\", ok", "b", "", "c", null)));
    assertThat(text.split("\r\n")[1]).isEqualTo("\"say \"\"hi\"\", ok\",\"\",");
  }

  @Test
  void neutralisesFormulaInColumnNamesAndKeepsMultilineText() {
    String text =
        csv(List.of(col("=evil", "STRING")), List.of(Json.obj("=evil", "dòng 1\r\ndòng 2")));
    assertThat(text).startsWith("\"'=evil\"\r\n");
    assertThat(text).endsWith("\"dòng 1\r\ndòng 2\"\r\n");
  }

  @Test
  void emptyResultStillHasHeader() {
    assertThat(csv(List.of(col("x", "STRING")), List.of())).isEqualTo("\"x\"\r\n");
  }
}
