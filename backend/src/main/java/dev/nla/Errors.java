package dev.nla;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

@RestControllerAdvice
public class Errors {
  static String correlation() {
    return Optional.ofNullable(MDC.get("correlationId"))
        .orElseGet(() -> UUID.randomUUID().toString());
  }

  static Map<String, Object> body(String code, String message, Object details) {
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

// Runs before Spring Security so 401/403 responses also carry X-Correlation-ID.
@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
class CorrelationFilter extends OncePerRequestFilter {
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader("X-Correlation-ID");
    String id =
        incoming != null && incoming.matches("[a-zA-Z0-9_-]{1,100}")
            ? incoming
            : UUID.randomUUID().toString();
    MDC.put("correlationId", id);
    response.setHeader("X-Correlation-ID", id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove("correlationId");
    }
  }
}
