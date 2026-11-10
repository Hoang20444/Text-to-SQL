package com.fsa.textsql.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsa.textsql.common.ApiException;
import com.fsa.textsql.warehouse.Engine;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Parsed semantic model snapshot of a dataset (docs/contracts/semantic-model.md). */
public record SemanticModel(
    List<Map<String, Object>> metrics,
    List<Map<String, Object>> dimensions,
    List<Map<String, Object>> relationships,
    List<Map<String, Object>> examples,
    List<String> businessRules,
    String semanticVersion,
    String schemaVersion,
    ZoneId timezone) {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  static SemanticModel parse(String raw, ObjectMapper json) {
    Map<String, Object> model;
    try {
      model = json.readValue(raw, MAP);
    } catch (JsonProcessingException e) {
      throw invalid();
    }
    try {
      return new SemanticModel(
          objects(model.get("metrics")),
          objects(model.get("dimensions")),
          objects(model.get("relationships")),
          objects(model.get("examples")),
          strings(model.get("businessRules")),
          text(model.get("semanticVersion")),
          text(model.get("schemaVersion")),
          ZoneId.of(text(model.get("timezone"))));
    } catch (IllegalArgumentException | DateTimeException e) {
      throw invalid();
    }
  }

  /** Examples written for the engine's dialect, in their declared order. */
  public List<Map<String, Object>> examplesFor(Engine engine) {
    return examples.stream().filter(e -> engine.name().equals(e.get("sqlDialect"))).toList();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> objects(Object value) {
    if (!(value instanceof List<?> list) || !list.stream().allMatch(v -> v instanceof Map)) {
      throw new IllegalArgumentException("expected an array of objects");
    }
    return (List<Map<String, Object>>) list;
  }

  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> list) || !list.stream().allMatch(v -> v instanceof String)) {
      throw new IllegalArgumentException("expected an array of strings");
    }
    return list.stream().map(String.class::cast).toList();
  }

  private static String text(Object value) {
    if (!(value instanceof String s) || s.isBlank()) {
      throw new IllegalArgumentException("expected a non-empty string");
    }
    return s;
  }

  private static ApiException invalid() {
    return new ApiException(
        HttpStatus.CONFLICT,
        "SEMANTIC_MODEL_INVALID",
        "Tập dữ liệu chưa có mô hình ngữ nghĩa hợp lệ.",
        false,
        Map.of());
  }
}
