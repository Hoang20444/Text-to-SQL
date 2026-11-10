package com.fsa.textsql.query;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** ChartSpec from docs/contracts/domain.md. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartSpec(String type, String title, String x, List<Series> series, String reason) {

  public ChartSpec {
    series = series == null ? List.of() : List.copyOf(series);
  }

  public record Series(String column, String label) {}

  public static ChartSpec table(String reason) {
    return new ChartSpec("table", "Kết quả truy vấn", null, List.of(), reason);
  }
}
