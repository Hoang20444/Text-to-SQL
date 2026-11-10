package com.fsa.textsql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fsa.textsql.bootstrap.LocalChinookBootstrap;
import com.fsa.textsql.security.TestJwt;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the real application against a PostgreSQL 16 metadata database: Flyway migrations, the local
 * Chinook bootstrap and the list APIs all go through real persistence.
 */
@SpringBootTest(properties = {"nla.local-bootstrap.enabled=true", "spring.config.import="})
@AutoConfigureMockMvc
@Testcontainers
@Import(TestJwt.DecoderConfig.class)
class MetadataApiIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer<?> metadataDb = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired LocalChinookBootstrap bootstrap;

  // These tests cover the metadata APIs themselves, so they call as ADMIN (sees every warehouse).
  // Role and warehouse authorization is covered by AuthorizationIntegrationTest.
  private static MockHttpServletRequestBuilder adminGet(String url) {
    return get(url).header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.admin()));
  }

  private static MockHttpServletRequestBuilder adminPost(String url) {
    return post(url).header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(TestJwt.admin()));
  }

  @Test
  void flywayCreatesMetadataTables() {
    List<String> tables =
        jdbc.sql(
                "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = 'public' ORDER BY table_name")
            .query(String.class)
            .list();

    assertThat(tables)
        .contains("warehouses", "datasets", "warehouse_access", "flyway_schema_history");
  }

  @Test
  void listsThreeEnginesAsSafeSummaries() throws Exception {
    mvc.perform(adminGet("/api/v1/warehouses").param("sort", "name").param("direction", "ASC"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(3))
        .andExpect(jsonPath("$.totalPages").value(1))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.pageSize").value(20))
        .andExpect(
            jsonPath("$.items[*].engine").value(hasItems("POSTGRESQL", "MYSQL", "SQL_SERVER")))
        .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
        .andExpect(jsonPath("$.items[0].capabilities.readOnlyQuery").value(true))
        .andExpect(jsonPath("$.items[0]", not(hasKey("credentialReference"))))
        .andExpect(jsonPath("$.items[0]", not(hasKey("host"))))
        .andExpect(jsonPath("$.items[0]", not(hasKey("username"))))
        .andExpect(jsonPath("$.items[0]", not(hasKey("port"))));
  }

  @Test
  void responseNeverContainsCredentialReferenceOrSecretName() throws Exception {
    String body =
        mvc.perform(adminGet("/api/v1/warehouses")).andReturn().getResponse().getContentAsString();

    assertThat(body).doesNotContain("credentialReference", "NLA_CHINOOK", "nla_reader", "env:");
  }

  @Test
  void filtersWarehousesByEngineAndSearch() throws Exception {
    mvc.perform(adminGet("/api/v1/warehouses").param("engine", "MYSQL"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].warehouseId").value("wh-chinook-mysql"));

    mvc.perform(adminGet("/api/v1/warehouses").param("search", "sql server"))
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].engine").value("SQL_SERVER"));

    // LIKE wildcards are matched literally, so "%" must not match every row.
    mvc.perform(adminGet("/api/v1/warehouses").param("search", "%"))
        .andExpect(jsonPath("$.totalItems").value(0));
  }

  @Test
  void paginatesWithStableOrder() throws Exception {
    mvc.perform(
            adminGet("/api/v1/warehouses")
                .param("pageSize", "2")
                .param("page", "2")
                .param("sort", "name")
                .param("direction", "ASC"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.totalItems").value(3))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.items[0].name").value("Chinook (SQL Server)"));
  }

  @Test
  void listsDatasetsWithQualifiedAllowlistAndVersionsFromDataFiles() throws Exception {
    mvc.perform(adminGet("/api/v1/datasets").param("warehouseId", "wh-chinook-pg"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].datasetId").value("ds-chinook-pg"))
        .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
        .andExpect(jsonPath("$.items[0].allowedSchemas[0]").value("public"))
        .andExpect(jsonPath("$.items[0].allowedTables.length()").value(11))
        .andExpect(jsonPath("$.items[0].allowedTables[0].schema").value("public"))
        .andExpect(jsonPath("$.items[0].semanticVersion").value("1.0.0"))
        .andExpect(jsonPath("$.items[0].schemaVersion").value("chinook-1.4.5"))
        .andExpect(jsonPath("$.items[0].updatedAt", containsString("Z")));

    mvc.perform(adminGet("/api/v1/datasets").param("warehouseId", "wh-chinook-mssql"))
        .andExpect(jsonPath("$.items[0].allowedTables[*].name").value(hasItems("InvoiceLine")))
        .andExpect(jsonPath("$.items[0].allowedSchemas[0]").value("dbo"));
  }

  @Test
  void bootstrapIsIdempotentAcrossRestarts() throws Exception {
    bootstrap.run(null);
    bootstrap.run(null);

    long warehouses = jdbc.sql("SELECT count(*) FROM warehouses").query(Long.class).single();
    long datasets = jdbc.sql("SELECT count(*) FROM datasets").query(Long.class).single();
    assertThat(warehouses).isEqualTo(3);
    assertThat(datasets).isEqualTo(3);
  }

  @Test
  void invalidParametersReturnErrorEnvelopeWithCorrelationId() throws Exception {
    mvc.perform(adminGet("/api/v1/warehouses").param("engine", "ORACLE"))
        .andExpect(status().isBadRequest())
        .andExpect(header().exists("X-Correlation-ID"))
        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.error.retryable").value(false))
        .andExpect(jsonPath("$.error.details.parameter").value("engine"))
        .andExpect(jsonPath("$.error.correlationId").exists());

    mvc.perform(adminGet("/api/v1/datasets").param("pageSize", "500"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

    mvc.perform(adminGet("/api/v1/datasets").param("unexpected", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.details.parameters[0]").value("unexpected"));
  }

  @Test
  void echoesSafeIncomingCorrelationIdInHeaderAndBody() throws Exception {
    mvc.perform(
            adminGet("/api/v1/warehouses")
                .param("status", "BROKEN")
                .header("X-Correlation-ID", "corr-test-123"))
        .andExpect(header().string("X-Correlation-ID", "corr-test-123"))
        .andExpect(jsonPath("$.error.correlationId").value("corr-test-123"));

    // An unsafe incoming ID (e.g. log injection) is replaced with a generated one.
    mvc.perform(adminGet("/api/v1/warehouses").header("X-Correlation-ID", "bad id\r\ninjected"))
        .andExpect(header().string("X-Correlation-ID", containsString("corr-")));
  }

  @Test
  void unknownRouteAndWrongMethodUseErrorEnvelope() throws Exception {
    mvc.perform(adminGet("/api/v1/does-not-exist"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

    mvc.perform(adminPost("/api/v1/warehouses"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
  }
}
