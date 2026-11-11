package dev.nla.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts a safe incoming X-Correlation-ID or creates one, and echoes it on every response. Runs
 * before Spring Security so 401/403 responses also carry the header.
 */
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
