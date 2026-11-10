package com.fsa.textsql.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns a correlation ID to every request, exposes it in the X-Correlation-ID response header and
 * keeps it in the logging MDC so error bodies and logs share the same ID.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-ID";
  public static final String MDC_KEY = "correlationId";

  // Only accept short, log-safe IDs from callers; anything else is replaced.
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader(HEADER);
    String id =
        incoming != null && SAFE_ID.matcher(incoming).matches()
            ? incoming
            : "corr-" + UUID.randomUUID();
    MDC.put(MDC_KEY, id);
    response.setHeader(HEADER, id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  /** Correlation ID of the current request, or a fresh one outside a request. */
  public static String current() {
    String id = MDC.get(MDC_KEY);
    return id != null ? id : "corr-" + UUID.randomUUID();
  }
}
