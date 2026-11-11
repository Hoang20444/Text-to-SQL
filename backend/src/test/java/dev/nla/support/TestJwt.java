package dev.nla.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.nla.security.Security;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Signs real RS256 tokens for tests. The test decoder uses the production validators (signature,
 * expiry, issuer, audience, subject); only the key source differs from Keycloak's JWK endpoint.
 */
public final class TestJwt {
  public static final String ISSUER = "http://127.0.0.1:8180/realms/nla";
  public static final String AUDIENCE = "nla-api";
  public static final String ADMIN_SUB = "sub-admin";
  public static final String ANALYST_SUB = "sub-analyst";
  public static final String VIEWER_SUB = "sub-viewer";

  private static final KeyPair TRUSTED = rsa();
  private static final KeyPair UNTRUSTED = rsa();

  private TestJwt() {}

  public static String admin() {
    return token(ADMIN_SUB, "admin", List.of("ADMIN"));
  }

  public static String analyst() {
    return token(ANALYST_SUB, "analyst", List.of("ANALYST"));
  }

  public static String viewer() {
    return token(VIEWER_SUB, "viewer", List.of("VIEWER"));
  }

  public static String token(String subject, String username, List<String> roles) {
    return sign(TRUSTED, claims(subject, username, roles, ISSUER, AUDIENCE, 300));
  }

  public static String signedByUntrustedKey() {
    return sign(UNTRUSTED, claims(ADMIN_SUB, "admin", List.of("ADMIN"), ISSUER, AUDIENCE, 300));
  }

  public static String withIssuer(String issuer) {
    return sign(TRUSTED, claims(ADMIN_SUB, "admin", List.of("ADMIN"), issuer, AUDIENCE, 300));
  }

  public static String withAudience(String audience) {
    return sign(TRUSTED, claims(ADMIN_SUB, "admin", List.of("ADMIN"), ISSUER, audience, 300));
  }

  public static String expired() {
    return sign(TRUSTED, claims(ADMIN_SUB, "admin", List.of("ADMIN"), ISSUER, AUDIENCE, -600));
  }

  public static String bearer(String token) {
    return "Bearer " + token;
  }

  private static JWTClaimsSet claims(
      String subject,
      String username,
      List<String> roles,
      String issuer,
      String audience,
      long expiresInSeconds) {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .issuer(issuer)
        .subject(subject)
        .audience(List.of(audience))
        .issueTime(Date.from(now.minusSeconds(Math.max(0, -expiresInSeconds) + 60)))
        .expirationTime(Date.from(now.plusSeconds(expiresInSeconds)))
        .claim("preferred_username", username)
        .claim("name", "Demo " + username)
        .claim("email", username + "@local.invalid")
        .claim("roles", roles)
        .build();
  }

  private static String sign(KeyPair key, JWTClaimsSet claims) {
    try {
      SignedJWT jwt =
          new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
      jwt.sign(new RSASSASigner(key.getPrivate()));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static KeyPair rsa() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Replaces the Keycloak JWK decoder with one trusting only the test signing key. */
  @TestConfiguration
  public static class DecoderConfig {
    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
      NimbusJwtDecoder decoder =
          NimbusJwtDecoder.withPublicKey((RSAPublicKey) TRUSTED.getPublic()).build();
      return Security.withValidators(decoder, ISSUER, AUDIENCE);
    }
  }
}
