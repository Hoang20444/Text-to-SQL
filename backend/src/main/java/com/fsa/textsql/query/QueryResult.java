package com.fsa.textsql.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** QueryResult from docs/contracts/domain.md. */
public record QueryResult(
    List<Column> columns,
    List<Map<String, Object>> rows,
    int returnedRowCount,
    int page,
    int pageSize,
    boolean truncated) {

  public record Column(
      String name,
      String label,
      String dataType,
      String semanticRole,
      @JsonInclude(JsonInclude.Include.NON_NULL) String unit) {}
}
