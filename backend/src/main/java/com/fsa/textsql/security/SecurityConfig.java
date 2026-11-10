package com.fsa.textsql.security;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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
 * Every /api/v1 endpoint requires a Keycloak-signed access token with one of the application roles.
 * Missing/invalid tokens give 401; valid tokens without an application role give 403.
 * Warehouse-level permissions are enforced by {@code WarehouseAccessService}.
 */
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain apiSecurity(HttpSecurity http, SecurityErrorHandler errors) throws Exception {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new JwtRolesConverter());
    http.csrf(csrf -> csrf.disable()) // stateless bearer-token API, no cookies
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/v1/**")
                    .hasAnyRole(AppRole.ADMIN.name(), AppRole.ANALYST.name(), AppRole.VIEWER.name())
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint(errors)
                    .accessDeniedHandler(errors))
        .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors));
    return http.build();
  }

  @Bean
  JwtDecoder jwtDecoder(SecurityProperties properties) {
    // Keys are fetched lazily from Keycloak, so the app can start before Keycloak is up.
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(properties.effectiveJwkSetUri()).build();
    return withValidators(decoder, properties.issuerUri(), properties.audience());
  }

  /** Signature is checked by the decoder; these validators add expiry, issuer, audience, sub. */
  public static JwtDecoder withValidators(
      NimbusJwtDecoder decoder, String issuer, String audience) {
    OAuth2TokenValidator<Jwt> audienceValidator =
        jwt ->
            jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Required audience is missing", null));
    OAuth2TokenValidator<Jwt> subjectValidator =
        jwt ->
            jwt.getSubject() != null && !jwt.getSubject().isBlank()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Subject is missing", null));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            List.of(
                JwtValidators.createDefaultWithIssuer(issuer),
                audienceValidator,
                subjectValidator)));
    return decoder;
  }
}
