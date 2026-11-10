package com.fsa.textsql.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps token roles to Spring authorities. Reads the flat {@code roles} claim (configured in the
 * realm) and Keycloak's default {@code realm_access.roles}. Unknown roles are ignored, so a token
 * can never grant something outside ADMIN, ANALYST, VIEWER.
 */
public class JwtRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    return roles(jwt).stream()
        .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role.authority()))
        .toList();
  }

  static Set<AppRole> roles(Jwt jwt) {
    Set<String> names = new LinkedHashSet<>(strings(jwt.getClaims().get("roles")));
    if (jwt.getClaims().get("realm_access") instanceof Map<?, ?> realmAccess) {
      names.addAll(strings(realmAccess.get("roles")));
    }
    Set<AppRole> roles = new LinkedHashSet<>();
    for (AppRole role : AppRole.values()) {
      if (names.contains(role.name())) {
        roles.add(role);
      }
    }
    return roles;
  }

  private static List<String> strings(Object claim) {
    if (claim instanceof Collection<?> values) {
      return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
    if (claim instanceof String[] values) {
      return Arrays.asList(values);
    }
    return List.of();
  }
}
