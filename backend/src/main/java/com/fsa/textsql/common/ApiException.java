package com.fsa.textsql.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Business error mapped to the shared error envelope in docs/contracts/errors.md. */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;
  private final boolean retryable;
  private final Map<String, Object> details;

  public ApiException(
      HttpStatus status,
      String code,
      String message,
      boolean retryable,
      Map<String, Object> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.retryable = retryable;
    this.details = details == null ? Map.of() : Map.copyOf(details);
  }

  public static ApiException invalidRequest(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, false, Map.of());
  }

  public static ApiException invalidRequest(String message, Map<String, Object> details) {
    return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, false, details);
  }

  public static ApiException forbidden(String message, Map<String, Object> details) {
    return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message, false, details);
  }

  public static ApiException notFound(String message) {
    return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message, false, Map.of());
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }

  public Map<String, Object> details() {
    return details;
  }
}
