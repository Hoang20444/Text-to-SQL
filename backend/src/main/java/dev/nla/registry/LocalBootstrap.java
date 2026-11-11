package dev.nla.registry;

import dev.nla.common.Json;
import dev.nla.store.Store;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registers the three provisioned local Chinook warehouses (PostgreSQL, MySQL, SQL Server) and one
 * dataset each. Tables come from live discovery; semantic metadata from data/chinook. Enabled only
 * when docker_chinook.py has written CHINOOK_DOCKER=true.
 */
@Component
@ConditionalOnProperty(name = "nla.local-bootstrap", havingValue = "true")
public class LocalBootstrap implements ApplicationRunner {
  private final Store store;
  private final CatalogService catalog;
  private final Environment env;
  private final TransactionTemplate tx;

  public LocalBootstrap(
      Store store, CatalogService catalog, Environment env, PlatformTransactionManager manager) {
    this.store = store;
    this.catalog = catalog;
    this.env = env;
    tx = new TransactionTemplate(manager);
  }

  static List<Map<String, Object>> specs(Environment env) {
    return List.of(
        Json.obj(
            "suffix",
            "pg",
            "engine",
            "POSTGRESQL",
            "port",
            55432,
            "schema",
            "public",
            "file",
            "postgresql",
            "secret",
            "PG"),
        Json.obj(
            "suffix",
            "mysql",
            "engine",
            "MYSQL",
            "port",
            env.getProperty("NLA_MYSQL_PORT", Integer.class, 53306),
            "schema",
            "nla_chinook",
            "file",
            "mysql",
            "secret",
            "MYSQL"),
        Json.obj(
            "suffix",
            "mssql",
            "engine",
            "SQL_SERVER",
            "port",
            51433,
            "schema",
            "dbo",
            "file",
            "mssql",
            "secret",
            "MSSQL"));
  }

  public void run(ApplicationArguments args) throws Exception {
    // Startup has no request principal; act as an explicit system admin that is audited.
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            "system-bootstrap", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    SecurityContextHolder.setContext(context);
    try {
      register();
      grantLocalAccounts();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  /**
   * Sample grants for the local Keycloak accounts (subjects from .local/oidc/backend.env). ADMIN
   * needs no grant; the viewer only gets PostgreSQL so "wrong warehouse -> 403" can be shown.
   */
  private void grantLocalAccounts() {
    String analyst = env.getProperty("NLA_LOCAL_ANALYST_SUBJECT", "");
    String viewer = env.getProperty("NLA_LOCAL_VIEWER_SUBJECT", "");
    for (String warehouseId : List.of("wh-chinook-pg", "wh-chinook-mysql", "wh-chinook-mssql")) {
      if (!analyst.isBlank() && store.exists("warehouse", warehouseId))
        store.grantWarehouseAccess(warehouseId, analyst);
    }
    if (!viewer.isBlank() && store.exists("warehouse", "wh-chinook-pg"))
      store.grantWarehouseAccess("wh-chinook-pg", viewer);
  }

  private void register() throws Exception {
    Path dataRoot = dataRoot(env.getProperty("nla.data-root", ""), Path.of(""));
    for (var spec : specs(env)) {
      String suffix = Json.str(spec, "suffix");
      String warehouseId = "wh-chinook-" + suffix;
      if (store.exists("warehouse", warehouseId)) continue;
      String now = Instant.now().toString();
      String database = "nla_chinook";
      var w =
          Json.obj(
              "warehouseId",
              warehouseId,
              "name",
              database + " (" + spec.get("engine") + ")",
              "engine",
              spec.get("engine"),
              "host",
              "127.0.0.1",
              "port",
              spec.get("port"),
              "database",
              database,
              "defaultSchema",
              spec.get("schema"),
              "username",
              "nla_reader",
              "status",
              "ACTIVE",
              "capabilities",
              Json.obj("readOnlyQuery", true, "metadataDiscovery", true),
              "createdAt",
              now,
              "updatedAt",
              now);
      Path path = dataRoot.resolve("chinook").resolve("semantic-" + spec.get("file") + ".json");
      var semantic = Json.read(Files.readString(path));
      tx.executeWithoutResult(
          status -> {
            store.insertWarehouse(w, "env:NLA_CHINOOK_" + spec.get("secret") + "_PASSWORD");
            var tables =
                catalog.discover(warehouseId, true).stream()
                    .filter(t -> !Json.str(t, "name").startsWith("nla_"))
                    .toList();
            long columnCount =
                tables.stream().mapToLong(t -> Json.objects(t.get("columns")).size()).sum();
            String schema = Json.str(spec, "schema");
            var d =
                Json.obj(
                    "datasetId",
                    "ds-chinook-" + suffix,
                    "warehouseId",
                    warehouseId,
                    "name",
                    database,
                    "description",
                    database
                        + ": phát hiện "
                        + tables.size()
                        + " bảng và "
                        + columnCount
                        + " cột trong schema "
                        + schema
                        + " trên "
                        + spec.get("engine")
                        + ".",
                    "allowedSchemas",
                    List.of(schema),
                    "allowedTables",
                    tables.stream()
                        .map(t -> Json.obj("schema", t.get("schema"), "name", t.get("name")))
                        .toList(),
                    "semanticVersion",
                    semantic.get("semanticVersion"),
                    "schemaVersion",
                    semantic.get("schemaVersion"),
                    "status",
                    "ACTIVE",
                    "createdAt",
                    now,
                    "updatedAt",
                    now);
            store.insertDataset(d, semantic);
            store.audit("LOCAL_CHINOOK_REGISTER", warehouseId);
          });
    }
  }

  /** Configured data root, else ./data or ../data relative to the working directory. */
  static Path dataRoot(String configured, Path workingDirectory) {
    if (!configured.isBlank()) return Path.of(configured);
    for (Path candidate :
        List.of(workingDirectory.resolve("data"), workingDirectory.resolve("../data"))) {
      if (Files.isDirectory(candidate.resolve("chinook"))) return candidate.normalize();
    }
    throw new IllegalStateException(
        "Không tìm thấy thư mục data/chinook; đặt NLA_DATA_ROOT trỏ tới thư mục data.");
  }
}
