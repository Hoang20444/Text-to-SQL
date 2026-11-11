package dev.nla.registry;

import dev.nla.common.ApiException;
import dev.nla.common.Errors;
import dev.nla.common.Json;
import dev.nla.common.Page;
import dev.nla.common.Requests;
import dev.nla.store.Store;
import dev.nla.warehouse.Adapters;
import dev.nla.warehouse.ConnectionDiagnosis;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Warehouse registry: list/read (T-1.1.1), register, update, connection test, discovery (T-2.1.1).
 */
@RestController
@RequestMapping("/api/v1")
public class WarehouseController {
  private final Store store;
  private final Adapters adapters;
  private final CatalogService catalog;

  public WarehouseController(Store store, Adapters adapters, CatalogService catalog) {
    this.store = store;
    this.adapters = adapters;
    this.catalog = catalog;
  }

  Map<String, Object> safe(Map<String, Object> w) {
    var out = new LinkedHashMap<>(w);
    if (!store.admin()) {
      for (String k : List.of("host", "port", "database", "defaultSchema", "username"))
        out.remove(k);
    }
    return out;
  }

  @GetMapping("/warehouses")
  Object warehouses(@RequestParam Map<String, String> p) {
    return Page.of(
        store.warehouses().stream().map(this::safe).toList(),
        p,
        "name",
        Set.of("name", "engine", "status", "createdAt", "updatedAt"));
  }

  @GetMapping("/warehouses/{id}")
  Object warehouse(@PathVariable String id) {
    return safe(store.warehouse(id));
  }

  @PostMapping("/warehouses")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<?> create(@RequestBody Map<String, Object> b) {
    Requests.fields(
        b,
        Set.of(
            "name",
            "engine",
            "host",
            "port",
            "database",
            "defaultSchema",
            "username",
            "credentialReference"));
    Requests.required(b, "name", "engine", "host", "database", "username", "credentialReference");
    adapters.validate(b);
    adapters.password(Json.str(b, "credentialReference"));
    String id = Json.id("wh");
    var doc = new LinkedHashMap<>(b);
    doc.remove("credentialReference");
    doc.putAll(
        Json.obj(
            "warehouseId",
            id,
            "status",
            "INVALID",
            "capabilities",
            adapters.get(doc).getCapabilities(),
            "createdAt",
            Instant.now().toString(),
            "updatedAt",
            Instant.now().toString()));
    store.insertWarehouse(doc, Json.str(b, "credentialReference"));
    store.audit("WAREHOUSE_REGISTER", id);
    return ResponseEntity.created(java.net.URI.create("/api/v1/warehouses/" + id)).body(safe(doc));
  }

  @PatchMapping("/warehouses/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  Object update(@PathVariable String id, @RequestBody Map<String, Object> b) {
    Requests.fields(
        b,
        Set.of(
            "name",
            "host",
            "port",
            "database",
            "defaultSchema",
            "username",
            "credentialReference",
            "status"));
    var w = store.warehouse(id);
    var changed = new LinkedHashMap<>(w);
    changed.putAll(b);
    changed.remove("credentialReference");
    adapters.validate(changed);
    String reference =
        b.containsKey("credentialReference")
            ? Json.str(b, "credentialReference")
            : store.credential(id);
    adapters.password(reference);
    if (b.containsKey("status") && !Set.of("ACTIVE", "DISABLED").contains(Json.str(b, "status")))
      throw ApiException.bad("Status không hợp lệ.");
    if ("ACTIVE".equals(b.get("status"))) {
      try {
        if (!adapters.get(changed).testConnection(changed, adapters.password(reference)))
          throw new Exception();
      } catch (Exception e) {
        throw new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "WAREHOUSE_CONNECTION_ERROR",
            "Không thể kích hoạt warehouse chưa kết nối được.");
      }
    }
    changed.put("updatedAt", Instant.now().toString());
    store.updateWarehouse(changed, reference);
    store.audit("WAREHOUSE_UPDATE", id);
    return safe(changed);
  }

  @PostMapping("/warehouses/{id}/test")
  @PreAuthorize("hasRole('ADMIN')")
  Object test(@PathVariable String id) {
    var w = store.warehouse(id);
    long start = System.nanoTime();
    boolean reachable = false;
    String reason = null;
    try {
      reachable = adapters.get(w).testConnection(w, adapters.password(store.credential(id)));
      if (!reachable) reason = "CONNECTION_FAILED";
    } catch (java.sql.SQLException e) {
      // Only a reason code is returned; driver messages and secrets stay server-side.
      reason = ConnectionDiagnosis.reason(e);
    } catch (ApiException e) {
      reason = e.status == HttpStatus.FORBIDDEN ? "HOST_NOT_ALLOWED" : "CREDENTIAL_NOT_CONFIGURED";
    }
    if (!Json.str(w, "status").equals("DISABLED")) {
      w.put("status", reachable ? "ACTIVE" : "UNAVAILABLE");
      store.updateWarehouse(w, null);
    }
    store.audit("WAREHOUSE_TEST", id);
    return Json.obj(
        "warehouseId",
        id,
        "reachable",
        reachable,
        "latencyMs",
        (System.nanoTime() - start) / 1000000,
        "checkedAt",
        Instant.now().toString(),
        "error",
        reachable
            ? null
            : Errors.body(
                "WAREHOUSE_CONNECTION_ERROR",
                ConnectionDiagnosis.message(reason),
                Json.obj("reason", reason)));
  }

  @PostMapping("/warehouses/{id}/discover")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<?> discover(@PathVariable String id) {
    store.warehouse(id);
    String runId = Json.id("discover");
    var run =
        Json.obj(
            "runId",
            runId,
            "warehouseId",
            id,
            "status",
            "RUNNING",
            "createdAt",
            Instant.now().toString(),
            "completedAt",
            null,
            "metadata",
            null,
            "error",
            null);
    store.run(runId, "DISCOVERY", run);
    try {
      var tables = catalog.discover(id, true);
      String schemaVersion = CatalogService.schemaVersion(tables);
      run.put("schemaVersion", schemaVersion);
      run.put("tableCount", tables.size());
      // A successful discovery proves the connection and records the structure version.
      var w = store.warehouse(id);
      w.put("schemaVersion", schemaVersion);
      w.put("lastDiscoveredAt", Instant.now().toString());
      if (!Json.str(w, "status").equals("DISABLED")) w.put("status", "ACTIVE");
      w.put("updatedAt", Instant.now().toString());
      store.updateWarehouse(w, null);
      var schemas = new TreeMap<String, List<Map<String, Object>>>();
      for (var table : tables)
        schemas.computeIfAbsent(Json.str(table, "schema"), k -> new ArrayList<>()).add(table);
      run.put(
          "metadata",
          Json.obj(
              "schemas",
              schemas.entrySet().stream()
                  .map(e -> Json.obj("name", e.getKey(), "tables", e.getValue()))
                  .toList()));
      run.put("status", "SUCCESS");
    } catch (ApiException e) {
      run.put("status", "FAILED");
      run.put("error", Errors.body(e.code, e.getMessage(), e.details));
    }
    run.put("completedAt", Instant.now().toString());
    store.run(runId, "DISCOVERY", run);
    store.audit("METADATA_DISCOVER", id);
    return ResponseEntity.accepted().body(run);
  }

  @GetMapping("/warehouses/{id}/discoveries/{runId}")
  Object discovery(@PathVariable String id, @PathVariable String runId) {
    store.warehouse(id);
    var run = store.run(runId, "DISCOVERY");
    if (!id.equals(run.get("warehouseId"))) throw ApiException.notFound();
    return run;
  }
}
