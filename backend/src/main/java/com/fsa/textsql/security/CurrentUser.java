package com.fsa.textsql.security;

import com.fsa.textsql.common.ApiException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The authenticated caller, read from the verified access token. */
public record CurrentUser(
    String subject,
    String username,
    String displayName,
    String email,
    Set<AppRole> roles,
    Instant expiresAt) {

  public boolean isAdmin() {
    return roles.contains(AppRole.ADMIN);
  }

  /** Current caller; 401 if the request is not authenticated with a JWT. */
  public static CurrentUser require() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (!(auth instanceof JwtAuthenticationToken token)) {
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Cần đăng nhập.", false, Map.of());
    }
    var jwt = token.getToken();
    String username = firstNonBlank(jwt.getClaimAsString("preferred_username"), jwt.getSubject());
    return new CurrentUser(
        jwt.getSubject(),
        username,
        firstNonBlank(jwt.getClaimAsString("name"), username),
        jwt.getClaimAsString("email"),
        JwtRolesConverter.roles(jwt),
        jwt.getExpiresAt());
  }

  private static String firstNonBlank(String value, String fallback) {
    return value != null && !value.isBlank() ? value : fallback;
  }
}
