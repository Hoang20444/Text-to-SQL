package com.fsa.textsql.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OIDC settings (nla.security in application.yml). */
@ConfigurationProperties(prefix = "nla.security")
public record SecurityProperties(String issuerUri, String jwkSetUri, String audience) {

  public SecurityProperties {
    if (issuerUri == null || issuerUri.isBlank()) {
      throw new IllegalStateException("nla.security.issuer-uri (OIDC_ISSUER_URI) is required");
    }
    if (audience == null || audience.isBlank()) {
      throw new IllegalStateException("nla.security.audience (OIDC_AUDIENCE) is required");
    }
  }

  /** JWK set endpoint; defaults to the Keycloak certs endpoint of the issuer. */
  public String effectiveJwkSetUri() {
    if (jwkSetUri != null && !jwkSetUri.isBlank()) {
      return jwkSetUri;
    }
    return issuerUri.replaceAll("/+$", "") + "/protocol/openid-connect/certs";
  }
}
