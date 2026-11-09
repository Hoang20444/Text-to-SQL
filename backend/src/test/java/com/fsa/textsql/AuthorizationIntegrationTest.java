package com.fsa.textsql;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fsa.textsql.security.TestJwt;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-1.2.1 / US-1.2: 401 without a valid token, 403 for a wrong warehouse or missing role, and the
 * three roles ADMIN, ANALYST, VIEWER. Tokens are real RS256 JWTs checked by the production
 * validators; warehouse grants come from the local bootstrap (analyst: all 3, viewer: PostgreSQL).
 */
@SpringBootTest(
    properties = {
      "spring.config.import=",
      "nla.local-bootstrap.enabled=true",
      "NLA_LOCAL_ANALYST_SUBJECT=" + TestJwt.ANALYST_SUB,
      "NLA_LOCAL_VIEWER_SUBJECT=" + TestJwt.VIEWER_SUB
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(TestJwt.DecoderConfig.class)
class AuthorizationIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> metadataDb = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired MockMvc mvc;

  private ResultActions call(String url, String token) throws Exception {
    var request = get(url);
    if (token != null) {
      request.header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(token));
    }
    return mvc.perform(request);
  }

  private void expectUnauthenticated(String token) throws Exception {
    call("/api/v1/warehouses", token)
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer"))
        .andExpect(header().exists("X-Correlation-ID"))
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.error.correlationId").exists());
  }

  // ---- 401: no token or a token that fails verification ----

  @Test
  void requestWithoutTokenIsRejectedWith401() throws Exception {
    expectUnauthenticated(null);
    call("/api/v1/datasets", null).andExpect(status().isUnauthorized());
    call("/api/v1/auth/session", null).andExpect(status().isUnauthorized());
  }

  @Test
  void tokenSignedByAnotherKeyIsRejected() throws Exception {
    expectUnauthenticated(TestJwt.signedByUntrustedKey());
  }

  @Test
  void tamperedTokenIsRejected() throws Exception {
    // Change one character of the payload: the signature no longer matches.
    String token = TestJwt.viewer();
    String[] parts = token.split("\\.");
    char last = parts[1].charAt(parts[1].length() - 1);
    parts[1] = parts[1].substring(0, parts[1].length() - 1) + (last == 'A' ? 'B' : 'A');
    expectUnauthenticated(String.join(".", parts));
  }

  @Test
  void expiredWrongIssuerAndWrongAudienceTokensAreRejected() throws Exception {
    expectUnauthenticated(TestJwt.expired());
    expectUnauthenticated(TestJwt.withIssuer("http://evil.example/realms/nla"));
    expectUnauthenticated(TestJwt.withAudience("another-api"));
  }

  @Test
  void malformedTokenIsRejected() throws Exception {
    expectUnauthenticated("not-a-jwt");
  }

  // ---- 403: authenticated but not allowed ----

  @Test
  void tokenWithoutApplicationRoleIsForbidden() throws Exception {
    call("/api/v1/warehouses", TestJwt.token("sub-nobody", "nobody", List.of("offline_access")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
  }

  @Test
  void viewerAskingForAnotherWarehouseGets403() throws Exception {
    call("/api/v1/datasets?warehouseId=wh-chinook-mysql", TestJwt.viewer())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.error.details.warehouseId").value("wh-chinook-mysql"));
  }

  @Test
  void unknownWarehouseAlsoGives403SoExistenceIsNotRevealed() throws Exception {
    call("/api/v1/datasets?warehouseId=wh-does-not-exist", TestJwt.viewer())
        .andExpect(status().isForbidden());
  }

  @Test
  void userWithRoleButNoGrantsSeesNothing() throws Exception {
    String token = TestJwt.token("sub-new-user", "newuser", List.of("ANALYST"));
    call("/api/v1/warehouses", token)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(0));
    call("/api/v1/datasets?warehouseId=wh-chinook-pg", token).andExpect(status().isForbidden());
  }

  // ---- Allowed access per role ----

  @Test
  void viewerOnlySeesGrantedWarehouse() throws Exception {
    call("/api/v1/warehouses", TestJwt.viewer())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].warehouseId").value("wh-chinook-pg"));
    call("/api/v1/datasets", TestJwt.viewer())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].datasetId", contains("ds-chinook-pg")));
    call("/api/v1/datasets?warehouseId=wh-chinook-pg", TestJwt.viewer())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1));
  }

  @Test
  void analystSeesGrantedWarehouses() throws Exception {
    call("/api/v1/warehouses", TestJwt.analyst())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(3));
    call("/api/v1/datasets?warehouseId=wh-chinook-mssql", TestJwt.analyst())
        .andExpect(status().isOk());
  }

  @Test
  void adminSeesEveryWarehouseWithoutGrants() throws Exception {
    call("/api/v1/warehouses", TestJwt.admin())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(3));
    call("/api/v1/datasets?warehouseId=wh-chinook-mysql", TestJwt.admin())
        .andExpect(status().isOk());
  }

  // ---- Session API ----

  @Test
  void sessionReturnsUserRolesAndAccessibleWarehouses() throws Exception {
    call("/api/v1/auth/session", TestJwt.viewer())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subject").value(TestJwt.VIEWER_SUB))
        .andExpect(jsonPath("$.username").value("viewer"))
        .andExpect(jsonPath("$.displayName").value("Demo viewer"))
        .andExpect(jsonPath("$.email").value("viewer@local.invalid"))
        .andExpect(jsonPath("$.roles", contains("VIEWER")))
        .andExpect(jsonPath("$.warehouseIds", contains("wh-chinook-pg")))
        .andExpect(jsonPath("$.expiresAt").exists());

    call("/api/v1/auth/session", TestJwt.admin())
        .andExpect(jsonPath("$.roles", contains("ADMIN")))
        .andExpect(
            jsonPath(
                "$.warehouseIds",
                containsInAnyOrder("wh-chinook-pg", "wh-chinook-mysql", "wh-chinook-mssql")));
  }

  @Test
  void sessionNeverEchoesTheToken() throws Exception {
    String token = TestJwt.analyst();
    String body =
        call("/api/v1/auth/session", token).andReturn().getResponse().getContentAsString();
    org.assertj.core.api.Assertions.assertThat(body).doesNotContain(token);
  }
}
