package com.fsa.textsql.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsa.textsql.common.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** Writes 401/403 from the security filter chain in the shared error envelope. */
@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final ObjectMapper json;

  public SecurityErrorHandler(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
      throws IOException {
    // Do not echo token validation details: they help attackers more than users.
    response.setHeader("WWW-Authenticate", "Bearer");
    write(
        response,
        HttpServletResponse.SC_UNAUTHORIZED,
        "UNAUTHENTICATED",
        "Cần đăng nhập hoặc phiên đăng nhập đã hết hạn.");
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
      throws IOException {
    write(
        response,
        HttpServletResponse.SC_FORBIDDEN,
        "FORBIDDEN",
        "Không có quyền thực hiện thao tác này.");
  }

  private void write(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    json.writeValue(response.getOutputStream(), ErrorResponse.of(code, message, false, Map.of()));
  }
}
