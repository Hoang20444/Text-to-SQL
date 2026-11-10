package com.fsa.textsql.catalog;

import com.fsa.textsql.access.WarehouseAccessService;
import com.fsa.textsql.common.ApiException;
import com.fsa.textsql.common.CorrelationIdFilter;
import com.fsa.textsql.dataset.Dataset;
import com.fsa.textsql.dataset.DatasetRepository;
import com.fsa.textsql.dataset.DatasetStatus;
import com.fsa.textsql.dataset.TableRef;
import com.fsa.textsql.query.WarehouseConnector;
import com.fsa.textsql.security.CurrentUser;
import com.fsa.textsql.warehouse.WarehouseConnection;
import com.fsa.textsql.warehouse.WarehouseRepository;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

/**
 * Background schema discovery (T-2.1.1). A request returns immediately with a PENDING run; a
 * bounded worker pool reads the warehouse with the read-only account, stores the snapshot and
 * schemaVersion, and re-validates the datasets of that warehouse against the new structure.
 */
@Service
public class DiscoveryService implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

  private final WarehouseRepository warehouses;
  private final DatasetRepository datasets;
  private final DiscoveryRunRepository runs;
  private final WarehouseAccessService access;
  private final WarehouseConnector connector;
  private final SchemaDiscovery discovery;
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(
          2,
          2,
          60,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(16),
          runnable -> {
            Thread thread = new Thread(runnable, "schema-discovery");
            thread.setDaemon(true);
            return thread;
          });

  public DiscoveryService(
      WarehouseRepository warehouses,
      DatasetRepository datasets,
      DiscoveryRunRepository runs,
      WarehouseAccessService access,
      WarehouseConnector connector,
      SchemaDiscovery discovery) {
    this.warehouses = warehouses;
    this.datasets = datasets;
    this.runs = runs;
    this.access = access;
    this.connector = connector;
    this.discovery = discovery;
  }

  @Override
  public void run(ApplicationArguments args) {
    int interrupted = runs.failInterrupted();
    if (interrupted > 0) {
      log.warn("Marked {} interrupted discovery run(s) as FAILED", interrupted);
    }
  }

  @PreDestroy
  void shutdown() {
    workers.shutdownNow();
  }

  /** ADMIN only. Returns the new run, or the run already in progress for this warehouse. */
  public DiscoveryRun start(String warehouseId, CurrentUser user) {
    if (!user.isAdmin()) {
      throw ApiException.forbidden("Chỉ quản trị viên được quét cấu trúc kho dữ liệu.", Map.of());
    }
    WarehouseConnection warehouse =
        warehouses
            .findConnection(warehouseId)
            .orElseThrow(() -> ApiException.notFound("Không tìm thấy kho dữ liệu."));
    String runId = "disc-" + UUID.randomUUID();
    DiscoveryRunRepository.CreateResult created =
        runs.createPending(runId, warehouseId, user.subject());
    if (!created.created()) {
      return created.run();
    }
    String correlationId = CorrelationIdFilter.current();
    try {
      workers.execute(() -> execute(warehouse, runId, correlationId));
    } catch (RejectedExecutionException busy) {
      runs.markFailed(runId, "DISCOVERY_BUSY");
    }
    return runs.find(warehouseId, runId).orElseThrow();
  }

  public DiscoveryRun get(String warehouseId, String runId, CurrentUser user) {
    access.requireAccess(user, warehouseId);
    return runs.find(warehouseId, runId)
        .orElseThrow(() -> ApiException.notFound("Không tìm thấy lượt quét."));
  }

  void execute(WarehouseConnection warehouse, String runId, String correlationId) {
    MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
    try {
      runs.markRunning(runId);
      SchemaSnapshot snapshot;
      try (Connection connection = connector.openReadOnly(warehouse)) {
        snapshot = discovery.discover(connection, warehouse.engine(), warehouse.database());
      }
      String version = discovery.schemaVersion(snapshot);
      runs.markSuccess(runId, snapshot, version);
      warehouses.recordDiscovery(warehouse.id(), version, runId);
      revalidateDatasets(warehouse.id(), snapshot, version);
      log.info(
          "Discovery {} for {} found {} table(s), schemaVersion={}",
          runId,
          warehouse.id(),
          snapshot.tables().size(),
          version);
    } catch (ApiException e) {
      Object reason = e.details().get("reason");
      runs.markFailed(runId, reason == null ? e.code() : e.code() + ":" + reason);
    } catch (SQLException e) {
      log.warn(
          "Discovery {} failed sqlState={} vendorCode={}",
          runId,
          e.getSQLState(),
          e.getErrorCode());
      runs.markFailed(runId, "DISCOVERY_FAILED");
    } catch (RuntimeException e) {
      log.error("Discovery {} failed type={}", runId, e.getClass().getName());
      runs.markFailed(runId, "DISCOVERY_FAILED");
    } finally {
      MDC.remove(CorrelationIdFilter.MDC_KEY);
    }
  }

  /**
   * A dataset whose allowed tables all still exist adopts the new schemaVersion (and leaves
   * INVALID_SCHEMA); a dataset that lost a table becomes INVALID_SCHEMA and cannot be queried.
   */
  private void revalidateDatasets(String warehouseId, SchemaSnapshot snapshot, String version) {
    Set<TableRef> present = new HashSet<>();
    snapshot.tables().forEach(t -> present.add(t.ref()));
    for (Dataset dataset : datasets.findByWarehouse(warehouseId)) {
      if (dataset.status() == DatasetStatus.DISABLED) {
        continue;
      }
      boolean complete = present.containsAll(dataset.allowedTables());
      datasets.updateSchemaState(
          dataset.datasetId(),
          complete ? version : dataset.schemaVersion(),
          complete ? DatasetStatus.ACTIVE : DatasetStatus.INVALID_SCHEMA);
    }
  }
}
