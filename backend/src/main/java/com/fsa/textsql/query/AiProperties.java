package com.fsa.textsql.query;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Private Python AI service settings (nla.ai in application.yml). */
@ConfigurationProperties(prefix = "nla.ai")
public record AiProperties(String url, String token, int timeoutSeconds) {

  public AiProperties {
    if (url == null || url.isBlank()) {
      throw new IllegalStateException("nla.ai.url (PYTHON_AI_URL) is required");
    }
    if (timeoutSeconds < 1) {
      throw new IllegalStateException("nla.ai.timeout-seconds must be positive");
    }
  }

  @Override
  public String toString() {
    return "AiProperties[url=" + url + ", token=***]";
  }
}
