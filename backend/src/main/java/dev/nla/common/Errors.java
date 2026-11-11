package dev.nla.common;

import java.util.*;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  public static String correlation() {
    return Optional.ofNullable(MDC.get("correlationId"))
        .orElseGet(() -> UUID.randomUUID().toString());
  }

  public static Map<String, Object> body(String code, String message, Object details) {
    return Json.obj(
        "code",
        code,
        "message",
        message,
        "correlationId",
        correlation(),
        "retryable",
        "QUERY_BUSY".equals(code),
        "details",
        details);
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException e) {
    return ResponseEntity.status(e.status)
        .body(Json.obj("error", body(e.code, e.getMessage(), e.details)));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(
            Json.obj(
                "error", body("INVALID_REQUEST", "Dữ liệu yêu cầu không hợp lệ.", Json.obj())));
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<?> denied(AccessDeniedException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(
            Json.obj("error", body("FORBIDDEN", "Không có quyền thực hiện thao tác.", Json.obj())));
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<?> missing(Exception e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(
            Json.obj(
                "error", body("RESOURCE_NOT_FOUND", "Không tìm thấy tài nguyên.", Json.obj())));
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<?> method(Exception e) {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(
            Json.obj(
                "error", body("INVALID_REQUEST", "Phương thức không được hỗ trợ.", Json.obj())));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> other(Exception e) {
    org.slf4j.LoggerFactory.getLogger(Errors.class)
        .error("Request failed: {} correlationId={}", e.getClass().getSimpleName(), correlation());
    return ResponseEntity.internalServerError()
        .body(Json.obj("error", body("INTERNAL_ERROR", "Không thể xử lý yêu cầu.", Json.obj())));
  }
}
