package com.fsa.textsql.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsa.textsql.access.WarehouseAccessRepository;
import com.fsa.textsql.bootstrap.LocalBootstrapProperties.AccessSeed;
import com.fsa.textsql.bootstrap.LocalBootstrapProperties.DatasetSeed;
import com.fsa.textsql.bootstrap.LocalBootstrapProperties.WarehouseSeed;
import com.fsa.textsql.dataset.DatasetRegistration;
import com.fsa.textsql.dataset.DatasetRepository;
import com.fsa.textsql.dataset.DatasetStatus;
import com.fsa.textsql.dataset.TableRef;
import com.fsa.textsql.warehouse.WarehouseRegistration;
import com.fsa.textsql.warehouse.WarehouseRepository;
import com.fsa.textsql.warehouse.WarehouseStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registers the local Chinook warehouses provisioned by data/scripts/docker_chinook.py.
 *
 * <p>Runs only when nla.local-bootstrap.enabled=true (CHINOOK_DOCKER=true). It is idempotent:
 * existing rows are left untouched, so restarting never overwrites admin changes. Versions come
 * from the provenance files under data/chinook so they are not invented in code.
 */
@Component
@ConditionalOnProperty(prefix = "nla.local-bootstrap", name = "enabled", havingValue = "true")
public class LocalChinookBootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(LocalChinookBootstrap.class);

  private final LocalBootstrapProperties properties;
  private final WarehouseRepository warehouses;
  private final DatasetRepository datasets;
  private final WarehouseAccessRepository access;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final Path dataRoot;

  public LocalChinookBootstrap(
      LocalBootstrapProperties properties,
      WarehouseRepository warehouses,
      DatasetRepository datasets,
      WarehouseAccessRepository access,
      TransactionTemplate tx,
      ObjectMapper json,
      @Value("${nla.data-root:}") String dataRoot) {
    this.properties = properties;
    this.warehouses = warehouses;
    this.datasets = datasets;
    this.access = access;
    this.tx = tx;
    this.json = json;
    this.dataRoot = resolveDataRoot(dataRoot, Path.of("").toAbsolutePath());
  }

  /**
   * Uses NLA_DATA_ROOT when set. Otherwise finds the repository data/ folder from the working
   * directory, so the app starts the same whether an IDE runs it from the repo root or backend/.
   */
  static Path resolveDataRoot(String configured, Path workingDir) {
    if (configured != null && !configured.isBlank()) {
      return Path.of(configured);
    }
    for (Path candidate : List.of(workingDir.resolve("data"), workingDir.resolve("../data"))) {
      if (Files.isDirectory(candidate.resolve("chinook"))) {
        return candidate.normalize();
      }
    }
    throw new IllegalStateException(
        "Cannot find the repository data/ folder from "
            + workingDir
            + "; run from the repository root or backend/, or set NLA_DATA_ROOT");
  }

  @Override
  public void run(ApplicationArguments args) {
    for (WarehouseSeed seed : properties.warehouses()) {
      // Read versions before opening the transaction so a missing file fails without partial rows.
      JsonNode semantic = readSemantic(seed.dataset());
      DatasetRegistration dataset = toDataset(seed, semantic);
      WarehouseRegistration warehouse = toWarehouse(seed);
      Boolean inserted =
          tx.execute(
              status -> {
                boolean newWarehouse = warehouses.insertIfAbsent(warehouse);
                boolean newDataset = datasets.insertIfAbsent(dataset);
                return newWarehouse || newDataset;
              });
      log.info(
          "Local warehouse {} ({}) {}",
          seed.id(),
          seed.engine(),
          Boolean.TRUE.equals(inserted) ? "registered" : "already registered");
    }
    grantSampleAccess();
  }

  /** Grants the local Keycloak accounts their sample warehouses (idempotent). */
  private void grantSampleAccess() {
    for (AccessSeed seed : properties.access()) {
      if (seed.subject() == null || seed.subject().isBlank()) {
        continue; // infra/local/oidc.py init has not been run yet
      }
      Integer added =
          tx.execute(
              status ->
                  (int)
                      seed.warehouses().stream()
                          .filter(id -> access.grantIfAbsent(seed.subject(), id))
                          .count());
      log.info(
          "Local warehouse grants: {} new for {} warehouse(s)", added, seed.warehouses().size());
    }
  }

  private WarehouseRegistration toWarehouse(WarehouseSeed seed) {
    return new WarehouseRegistration(
        seed.id(),
        seed.name(),
        seed.engine(),
        WarehouseStatus.ACTIVE,
        seed.host(),
        seed.port(),
        seed.database(),
        seed.defaultSchema(),
        seed.username(),
        seed.credentialReference());
  }

  private DatasetRegistration toDataset(WarehouseSeed seed, JsonNode semantic) {
    DatasetSeed d = seed.dataset();
    List<TableRef> tables =
        d.tables().stream().map(name -> new TableRef(seed.defaultSchema(), name)).toList();
    return new DatasetRegistration(
        d.id(),
        seed.id(),
        d.name(),
        d.description(),
        DatasetStatus.ACTIVE,
        List.of(seed.defaultSchema()),
        tables,
        requiredText(semantic, "semanticVersion", d.semanticFile()),
        requiredText(semantic, "schemaVersion", d.semanticFile()));
  }

  private JsonNode readSemantic(DatasetSeed dataset) {
    Path file = dataRoot.resolve(dataset.semanticFile()).normalize();
    try {
      return json.readTree(Files.readString(file));
    } catch (IOException e) {
      throw new IllegalStateException(
          "Cannot read semantic file "
              + file.toAbsolutePath()
              + "; set NLA_DATA_ROOT to the repository data/ folder",
          e);
    }
  }

  private static String requiredText(JsonNode node, String field, String file) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalStateException(file + " is missing " + field);
    }
    return value.asText();
  }
}
