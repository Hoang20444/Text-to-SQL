package com.fsa.textsql.common;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Converts every failure into the shared error envelope; never leaks stack traces or SQL. */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorResponse> handleApi(ApiException e) {
    return ResponseEntity.status(e.status())
        .body(ErrorResponse.of(e.code(), e.getMessage(), e.retryable(), e.details()));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    return ResponseEntity.badRequest()
        .body(
            ErrorResponse.of(
                "INVALID_REQUEST",
                "Tham số không hợp lệ: " + e.getName(),
                false,
                Map.of("parameter", e.getName())));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
    // Covers malformed JSON, wrong types and unknown fields; parser details are not echoed.
    return ResponseEntity.badRequest()
        .body(
            ErrorResponse.of(
                "INVALID_REQUEST",
                "Nội dung yêu cầu không hợp lệ hoặc chứa trường không được hỗ trợ.",
                false,
                Map.of()));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException e) {
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .body(
            ErrorResponse.of(
                "INVALID_REQUEST", "Chỉ hỗ trợ Content-Type application/json.", false, Map.of()));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(
            ErrorResponse.of("RESOURCE_NOT_FOUND", "Không tìm thấy tài nguyên.", false, Map.of()));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException e) {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(
            ErrorResponse.of(
                "INVALID_REQUEST", "Phương thức HTTP không được hỗ trợ.", false, Map.of()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
    // Log only the exception type: messages may contain SQL, hosts or other internal details.
    log.error("Unhandled request failure type={}", e.getClass().getName());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            ErrorResponse.of(
                "INTERNAL_ERROR", "Lỗi hệ thống, vui lòng thử lại sau.", false, Map.of()));
  }
}
