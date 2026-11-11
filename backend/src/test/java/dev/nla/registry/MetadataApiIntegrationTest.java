package dev.nla.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.nla.support.RegistryFixture;
import dev.nla.support.TestJwt;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** T-1.1.1: Flyway metadata store on PostgreSQL 16 and the warehouse/dataset list APIs. */
@SpringBootTest(
    properties = {
      "spring.config.import=",
      "nla.local-bootstrap=false",
      "OIDC_ISSUER_URI=" + TestJwt.ISSUER,
      "OIDC_AUDIENCE=" + TestJwt.AUDIENCE
    })
@AutoConfigureMockMvc
@Testcontainers
@Import(TestJwt.DecoderConfig.class)
class MetadataApiIntegrationTest {
  @Container @ServiceConnection
  static PostgreSQLContainer<?> metadataDb = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void seed() {
    RegistryFixture.reset(jdbc);
  }

  private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder r) {
    return r.header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(token));
  }

  @Test
  void flywayCreatesMetadataTables() {
    var tables =
        jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema='public'",
            String.class);
    assertThat(tables)
        .contains(
            "warehouse",
            "dataset",
            "query_history",
            "resource_access",
            "audit_event",
            "platform_settings",
            "flyway_schema_history");
  }

  @Test
  void adminListsThreeEnginesWithPagingEnvelope() throws Exception {
    mvc.perform(
            as(TestJwt.admin(), get("/api/v1/warehouses"))
                .param("sort", "name")
                .param("direction", "ASC"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(3))
        .andExpect(jsonPath("$.totalPages").value(1))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.pageSize").value(20))
        .andExpect(
            jsonPath("$.items[*].engine").value(hasItems("POSTGRESQL", "MYSQL", "SQL_SERVER")))
        .andExpect(jsonPath("$.items[0].name").value("Chinook (MySQL)"))
        .andExpect(jsonPath("$.items[0].capabilities.readOnlyQuery").value(true))
        .andExpect(jsonPath("$.items[0].host").value("127.0.0.1"));
  }

  @Test
  void nonAdminDoesNotSeeConnectionFields() throws Exception {
    mvc.perform(as(TestJwt.analyst(), get("/api/v1/warehouses")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0]", not(hasKey("host"))))
        .andExpect(jsonPath("$.items[0]", not(hasKey("port"))))
        .andExpect(jsonPath("$.items[0]", not(hasKey("username"))))
        .andExpect(jsonPath("$.items[0]", not(hasKey("database"))));
  }

  @Test
  void responsesNeverContainCredentialReferences() throws Exception {
    for (String url : new String[] {"/api/v1/warehouses", "/api/v1/datasets"}) {
      String body =
          mvc.perform(as(TestJwt.admin(), get(url))).andReturn().getResponse().getContentAsString();
      assertThat(body).doesNotContain("credential", "env:", "NLA_TEST_");
    }
  }

  @Test
  void filtersSearchesAndPaginates() throws Exception {
    mvc.perform(as(TestJwt.admin(), get("/api/v1/warehouses")).param("engine", "MYSQL"))
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].warehouseId").value("wh-chinook-mysql"));
    mvc.perform(as(TestJwt.admin(), get("/api/v1/warehouses")).param("search", "sql server"))
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].engine").value("SQL_SERVER"));
    mvc.perform(
            as(TestJwt.admin(), get("/api/v1/warehouses"))
                .param("pageSize", "2")
                .param("page", "2")
                .param("sort", "name")
                .param("direction", "ASC"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.items[0].name").value("Chinook (SQL Server)"));
  }

  @Test
  void listsDatasetsAndReadsOneById() throws Exception {
    mvc.perform(as(TestJwt.admin(), get("/api/v1/datasets")).param("warehouseId", "wh-chinook-pg"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].datasetId").value("ds-chinook-pg"))
        .andExpect(jsonPath("$.items[0].allowedTables[0].schema").value("public"))
        .andExpect(jsonPath("$.items[0].semanticVersion").value("1.0.0"));
    mvc.perform(as(TestJwt.admin(), get("/api/v1/datasets/ds-chinook-mssql")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.warehouseId").value("wh-chinook-mssql"));
    mvc.perform(as(TestJwt.admin(), get("/api/v1/datasets/ds-missing")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void invalidPagingUsesErrorEnvelopeWithCorrelationId() throws Exception {
    mvc.perform(
            as(TestJwt.admin(), get("/api/v1/datasets"))
                .param("pageSize", "500")
                .header("X-Correlation-ID", "corr-test-123"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("X-Correlation-ID", "corr-test-123"))
        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.error.retryable").value(false))
        .andExpect(jsonPath("$.error.correlationId").value("corr-test-123"));
    mvc.perform(as(TestJwt.admin(), get("/api/v1/warehouses")).param("sort", "password"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unsafeCorrelationIdIsReplaced() throws Exception {
    String id =
        mvc.perform(
                as(TestJwt.admin(), get("/api/v1/warehouses"))
                    .header("X-Correlation-ID", "bad id\r\ninjected"))
            .andReturn()
            .getResponse()
            .getHeader("X-Correlation-ID");
    assertThat(id).matches("[a-zA-Z0-9_-]{1,100}");
  }

  @Test
  void unknownRouteAndWrongMethodUseErrorEnvelope() throws Exception {
    mvc.perform(as(TestJwt.admin(), get("/api/v1/does-not-exist")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    mvc.perform(as(TestJwt.admin(), delete("/api/v1/warehouses")))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
  }

  @Test
  void dataRootIsFoundFromRepositoryRootOrBackendFolder(@TempDir Path repo) throws Exception {
    Files.createDirectories(repo.resolve("data/chinook"));
    Files.createDirectories(repo.resolve("backend"));
    assertThat(LocalBootstrap.dataRoot("", repo)).isEqualTo(repo.resolve("data").normalize());
    assertThat(LocalBootstrap.dataRoot("", repo.resolve("backend")))
        .isEqualTo(repo.resolve("data").normalize());
    assertThat(LocalBootstrap.dataRoot("/custom/data", repo)).isEqualTo(Path.of("/custom/data"));
  }
}
