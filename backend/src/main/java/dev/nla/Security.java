package dev.nla;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OIDC resource server (T-1.2.1). Every /api/v1 call needs a valid access token from the configured
 * issuer and audience, plus one of the application roles VIEWER, ANALYST or ADMIN in the "roles"
 * claim. There is no anonymous or local bypass.
 */
@Configuration
@EnableMethodSecurity
public class Security {
  static final Set<String> ROLES = Set.of("VIEWER", "ANALYST", "ADMIN");

  @Bean
  SecurityFilterChain chain(HttpSecurity http, JwtDecoder decoder) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.exceptionHandling(
        e ->
            e.authenticationEntryPoint(
                    (req, res, ex) -> {
                      res.setHeader("WWW-Authenticate", "Bearer");
                      send(res, 401, "UNAUTHENTICATED", "Cần đăng nhập.");
                    })
                .accessDeniedHandler(
                    (req, res, ex) ->
                        send(res, 403, "FORBIDDEN", "Không có quyền thực hiện thao tác.")));
    http.authorizeHttpRequests(
        a ->
            a.requestMatchers("/api/v1/**")
                .hasAnyRole("VIEWER", "ANALYST", "ADMIN")
                .anyRequest()
                .authenticated());
    http.oauth2ResourceServer(
        o ->
            o.authenticationEntryPoint(
                    (req, res, ex) -> {
                      res.setHeader("WWW-Authenticate", "Bearer");
                      send(res, 401, "UNAUTHENTICATED", "Token không hợp lệ hoặc đã hết hạn.");
                    })
                .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter())));
    return http.build();
  }

  @Bean
  JwtDecoder jwtDecoder(Environment env) {
    String issuer = env.getProperty("OIDC_ISSUER_URI", "");
    String audience = env.getProperty("OIDC_AUDIENCE", "");
    if (issuer.isBlank() || audience.isBlank())
      throw new IllegalStateException("OIDC_ISSUER_URI and OIDC_AUDIENCE are required");
    String jwkSetUri = env.getProperty("OIDC_JWK_SET_URI", "");
    if (jwkSetUri.isBlank()) jwkSetUri = issuer + "/protocol/openid-connect/certs";
    return withValidators(NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build(), issuer, audience);
  }

  /** Signature and expiry plus issuer, audience and a non-blank subject. */
  public static NimbusJwtDecoder withValidators(
      NimbusJwtDecoder decoder, String issuer, String audience) {
    OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
    OAuth2TokenValidator<Jwt> audienceValidator =
        jwt -> {
          Object claim = jwt.getClaims().get("aud");
          boolean valid =
              claim instanceof String value
                  ? audience.equals(value)
                  : claim instanceof Collection<?> values && values.contains(audience);
          return valid
              ? OAuth2TokenValidatorResult.success()
              : OAuth2TokenValidatorResult.failure(
                  new OAuth2Error("invalid_token", "Required audience is missing", null));
        };
    OAuth2TokenValidator<Jwt> subjectValidator =
        jwt ->
            jwt.getSubject() != null && !jwt.getSubject().isBlank()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Required subject is missing", null));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator, subjectValidator));
    return decoder;
  }

  static JwtAuthenticationConverter converter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(Security::authorities);
    return converter;
  }

  static Collection<GrantedAuthority> authorities(Jwt jwt) {
    Object claim = jwt.getClaims().get("roles");
    if (!(claim instanceof List<?> roles)) return List.of();
    return roles.stream()
        .filter(String.class::isInstance)
        .map(Object::toString)
        .filter(ROLES::contains)
        .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
        .toList();
  }

  static void send(HttpServletResponse res, int status, String code, String message)
      throws IOException {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter().write(Json.write(Json.obj("error", Errors.body(code, message, Json.obj()))));
  }
}
