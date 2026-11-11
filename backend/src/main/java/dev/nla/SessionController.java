package dev.nla;

import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** GET /api/v1/auth/session: signed-in user, roles and accessible warehouses (no token echo). */
@RestController
@RequestMapping("/api/v1/auth")
public class SessionController {
  private final Store store;

  public SessionController(Store store) {
    this.store = store;
  }

  @GetMapping("/session")
  Map<String, Object> session(@AuthenticationPrincipal Jwt jwt) {
    var roles =
        Security.authorities(jwt).stream()
            .map(a -> a.getAuthority().substring("ROLE_".length()))
            .sorted()
            .toList();
    return Json.obj(
        "subject",
        jwt.getSubject(),
        "username",
        jwt.getClaimAsString("preferred_username"),
        "displayName",
        jwt.getClaimAsString("name"),
        "email",
        jwt.getClaimAsString("email"),
        "roles",
        roles,
        "warehouseIds",
        store.accessibleWarehouseIds(),
        "expiresAt",
        jwt.getExpiresAt() == null ? null : jwt.getExpiresAt().toString());
  }
}
