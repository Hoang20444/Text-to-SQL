package com.fsa.textsql.bootstrap;

import com.fsa.textsql.warehouse.Engine;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Local sample warehouses and warehouse grants registered at startup (nla.local-bootstrap in
 * application.yml).
 */
@ConfigurationProperties(prefix = "nla.local-bootstrap")
public record LocalBootstrapProperties(
    boolean enabled, List<AccessSeed> access, List<WarehouseSeed> warehouses) {

  public LocalBootstrapProperties {
    access = access == null ? List.of() : List.copyOf(access);
    warehouses = warehouses == null ? List.of() : List.copyOf(warehouses);
  }

  /** Grants an OIDC subject access to warehouses; blank subjects are skipped. */
  public record AccessSeed(String subject, List<String> warehouses) {

    public AccessSeed {
      warehouses = warehouses == null ? List.of() : List.copyOf(warehouses);
    }
  }

  public record WarehouseSeed(
      String id,
      String name,
      Engine engine,
      String host,
      int port,
      String database,
      String defaultSchema,
      String username,
      String credentialReference,
      DatasetSeed dataset) {}

  public record DatasetSeed(
      String id, String name, String description, String semanticFile, List<String> tables) {}
}
