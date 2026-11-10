package com.fsa.textsql.query;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Read-only execution limits (nla.query in application.yml). */
@ConfigurationProperties(prefix = "nla.query")
public record QueryProperties(
    int maxRows,
    int statementTimeoutSeconds,
    int loginTimeoutSeconds,
    String allowedHosts,
    String mysqlRsaPublicKey) {

  public QueryProperties {
    if (maxRows < 1 || statementTimeoutSeconds < 1 || loginTimeoutSeconds < 1) {
      throw new IllegalStateException("nla.query limits must be positive");
    }
  }

  public Set<String> allowedHostSet() {
    if (allowedHosts == null || allowedHosts.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(allowedHosts.split(","))
        .map(String::strip)
        .filter(h -> !h.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }
}
