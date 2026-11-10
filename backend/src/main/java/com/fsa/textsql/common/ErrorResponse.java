package com.fsa.textsql.common;

import java.util.Map;

/** Error envelope: {@code {"error": {code, message, correlationId, retryable, details}}}. */
public record ErrorResponse(ErrorBody error) {

  public record ErrorBody(
      String code,
      String message,
      String correlationId,
      boolean retryable,
      Map<String, Object> details) {}

  public static ErrorResponse of(
      String code, String message, boolean retryable, Map<String, Object> details) {
    return new ErrorResponse(
        new ErrorBody(code, message, CorrelationIdFilter.current(), retryable, details));
  }
}
