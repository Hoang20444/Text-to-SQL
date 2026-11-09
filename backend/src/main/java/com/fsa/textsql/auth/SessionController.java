package com.fsa.textsql.auth;

import com.fsa.textsql.access.WarehouseAccessService;
import com.fsa.textsql.security.AppRole;
import com.fsa.textsql.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class SessionController {

  private final WarehouseAccessService access;

  public SessionController(WarehouseAccessService access) {
    this.access = access;
  }

  /** GET /api/v1/auth/session: who is signed in, their roles and accessible warehouses. */
  @GetMapping("/session")
  public SessionResponse session() {
    CurrentUser user = CurrentUser.require();
    return new SessionResponse(
        user.subject(),
        user.username(),
        user.displayName(),
        user.email(),
        user.roles().stream().toList(),
        access.accessibleWarehouseIds(user),
        user.expiresAt());
  }

  /** Never contains the token itself or any credential. */
  public record SessionResponse(
      String subject,
      String username,
      String displayName,
      String email,
      List<AppRole> roles,
      List<String> warehouseIds,
      Instant expiresAt) {}
}
