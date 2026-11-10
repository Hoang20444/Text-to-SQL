package com.fsa.textsql.query;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Candidate returned by the Python AI service (docs/contracts/java-python.md, 8 fields). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiCandidate(
    String sql,
    String explanation,
    List<String> assumptions,
    List<String> selectedMetrics,
    List<String> selectedDimensions,
    ChartSpec chartRecommendation,
    Double confidence,
    String model) {

  /** All eight fields are required; a candidate is not a permission to execute. */
  boolean isComplete() {
    return sql != null
        && !sql.isBlank()
        && explanation != null
        && assumptions != null
        && selectedMetrics != null
        && selectedDimensions != null
        && chartRecommendation != null
        && confidence != null
        && confidence >= 0
        && confidence <= 1
        && model != null
        && !model.isBlank();
  }
}
