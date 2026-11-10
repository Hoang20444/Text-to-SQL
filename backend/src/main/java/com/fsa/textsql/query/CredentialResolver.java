package com.fsa.textsql.query;

import com.fsa.textsql.common.ApiException;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code env:NAME} credential references to secrets held by the deployment (environment
 * variables or the ignored .local/*.env files). Secret values are never logged or returned.
 */
@Component
public class CredentialResolver {

  private static final Pattern REFERENCE = Pattern.compile("env:([A-Z][A-Z0-9_]{0,127})");

  private final Environment environment;

  public CredentialResolver(Environment environment) {
    this.environment = environment;
  }

  public String resolve(String reference) {
    var match = reference == null ? null : REFERENCE.matcher(reference);
    if (match == null || !match.matches()) {
      throw unavailable();
    }
    String secret = environment.getProperty(match.group(1));
    if (secret == null || secret.isEmpty()) {
      throw unavailable();
    }
    return secret;
  }

  private static ApiException unavailable() {
    // Same message for every case so the response does not reveal secret names.
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "WAREHOUSE_CONNECTION_ERROR",
        "Chưa cấu hình thông tin đăng nhập cho kho dữ liệu.",
        false,
        Map.of("reason", "CREDENTIAL_NOT_CONFIGURED"));
  }
}
