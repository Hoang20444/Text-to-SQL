package dev.nla.registry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nla.common.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** T-3.1.2: changing business definitions requires a new semanticVersion. */
class SemanticVersionsTest {
  private static Map<String, Object> model(String version, String expression) {
    return Json.obj(
        "semanticVersion",
        version,
        "timezone",
        "UTC",
        "metrics",
        List.of(Json.obj("name", "revenue", "expression", expression)),
        "dimensions",
        List.of(),
        "businessRules",
        List.of("Doanh thu chỉ tính hóa đơn đã thanh toán"));
  }

  @Test
  void sameVersionWithChangedExpressionIsAConflict() {
    var stored = model("1.0.0", "SUM(total)");
    var submitted = model("1.0.0", "SUM(total * 2)");

    assertThat(SemanticVersions.conflict("1.0.0", stored, submitted)).isTrue();
  }

  @Test
  void newVersionWithChangedExpressionIsAccepted() {
    var stored = model("1.0.0", "SUM(total)");
    var submitted = model("1.1.0", "SUM(total * 2)");

    assertThat(SemanticVersions.conflict("1.0.0", stored, submitted)).isFalse();
  }

  @Test
  void resubmittingTheSameDefinitionsKeepsTheVersion() {
    var stored = model("1.0.0", "SUM(total)");
    var submitted = model("1.0.0", "SUM(total)");

    assertThat(SemanticVersions.conflict("1.0.0", stored, submitted)).isFalse();
  }

  @Test
  void missingAndEmptyListsAreTheSame() {
    var stored = model("1.0.0", "SUM(total)");
    var submitted = model("1.0.0", "SUM(total)");
    submitted.remove("dimensions");

    assertThat(SemanticVersions.conflict("1.0.0", stored, submitted)).isFalse();
  }

  @Test
  void changedBusinessRuleOrTimezoneIsAConflict() {
    var stored = model("1.0.0", "SUM(total)");
    var rule = model("1.0.0", "SUM(total)");
    rule.put("businessRules", List.of("Tính cả hóa đơn hoàn tiền"));
    var zone = model("1.0.0", "SUM(total)");
    zone.put("timezone", "Asia/Ho_Chi_Minh");

    assertThat(SemanticVersions.conflict("1.0.0", stored, rule)).isTrue();
    assertThat(SemanticVersions.conflict("1.0.0", stored, zone)).isTrue();
  }

  @Test
  void versionFormatIsMajorMinorPatch() {
    assertThat(SemanticVersions.validFormat("1.2.3")).isTrue();
    assertThat(SemanticVersions.validFormat("1.2")).isFalse();
    assertThat(SemanticVersions.validFormat("v1.2.3")).isFalse();
    assertThat(SemanticVersions.validFormat(123)).isFalse();
  }
}
