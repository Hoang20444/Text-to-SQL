package dev.nla.registry;

import dev.nla.common.ApiException;
import dev.nla.common.Json;
import dev.nla.common.Page;
import dev.nla.common.Requests;
import dev.nla.store.Store;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Dataset registry: list/read (T-1.1.1), create and update against discovered tables (T-2.1.1). */
@RestController
@RequestMapping("/api/v1")
public class DatasetController {
  private final Store store;
  private final CatalogService catalog;

  public DatasetController(Store store, CatalogService catalog) {
    this.store = store;
    this.catalog = catalog;
  }

  @GetMapping("/datasets")
  Object datasets(@RequestParam Map<String, String> p) {
    // An explicit warehouse filter must be allowed, so a wrong warehouse is 403, not [].
    if (!p.getOrDefault("warehouseId", "").isBlank()) store.authorize(p.get("warehouseId"));
    var list =
        store.all("dataset").stream()
            .filter(d -> store.canAccess(Json.str(d, "warehouseId")))
            .toList();
    return Page.of(list, p, "name", Set.of("name", "status", "updatedAt", "createdAt"));
  }

  @GetMapping("/datasets/{id}")
  Object dataset(@PathVariable String id) {
    return store.dataset(id);
  }

  @PostMapping("/datasets")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<?> createDataset(@RequestBody Map<String, Object> b) {
    Requests.fields(
        b, Set.of("warehouseId", "name", "description", "allowedSchemas", "allowedTables"));
    Requests.required(b, "warehouseId", "name");
    String warehouse = Json.str(b, "warehouseId");
    var discovered = catalog.discover(warehouse, false);
    var allow = Json.objects(b.get("allowedTables"));
    Set<String> physical = new HashSet<>();
    discovered.forEach(t -> physical.add(CatalogService.key(t)));
    if (allow.isEmpty() || allow.stream().anyMatch(t -> !physical.contains(CatalogService.key(t))))
      throw ApiException.bad("Allowlist chứa bảng không tồn tại.");
    if (!(b.get("allowedSchemas") instanceof List<?> schemas)
        || allow.stream().anyMatch(t -> !schemas.contains(t.get("schema"))))
      throw ApiException.bad("Allowlist schema không hợp lệ.");
    // Versions come from the server, never from the UI (docs/contracts/domain.md).
    String schemaVersion =
        CatalogService.schemaVersion(
            discovered.stream()
                .filter(
                    t ->
                        allow.stream()
                            .anyMatch(a -> CatalogService.key(a).equals(CatalogService.key(t))))
                .toList());
    String id = Json.id("ds");
    var d = new LinkedHashMap<>(b);
    d.putAll(
        Json.obj(
            "datasetId",
            id,
            "semanticVersion",
            "0.0.0",
            "schemaVersion",
            schemaVersion,
            "status",
            "ACTIVE",
            "createdAt",
            Instant.now().toString(),
            "updatedAt",
            Instant.now().toString()));
    var sem =
        Json.obj(
            "tables",
            List.of(),
            "metrics",
            List.of(),
            "dimensions",
            List.of(),
            "relationships",
            List.of(),
            "examples",
            List.of(),
            "businessRules",
            List.of(),
            "timezone",
            "UTC",
            "semanticVersion",
            "0.0.0",
            "schemaVersion",
            schemaVersion);
    store.insertDataset(d, sem);
    store.audit("DATASET_REGISTER", id);
    return ResponseEntity.status(201).body(d);
  }

  @PatchMapping("/datasets/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  Object updateDataset(@PathVariable String id, @RequestBody Map<String, Object> b) {
    Requests.fields(b, Set.of("name", "description", "allowedSchemas", "allowedTables", "status"));
    var d = store.dataset(id);
    d.putAll(b);
    if (!Set.of("ACTIVE", "DISABLED").contains(Json.str(d, "status")))
      throw ApiException.bad("Status không hợp lệ.");
    var sem = store.semantic(id);
    if (Json.str(d, "status").equals("ACTIVE")) {
      var physical = catalog.discover(Json.str(d, "warehouseId"), true);
      Set<String> keys = new HashSet<>();
      physical.forEach(t -> keys.add(CatalogService.key(t)));
      var allowed = Json.objects(d.get("allowedTables"));
      if (allowed.stream().anyMatch(t -> !keys.contains(CatalogService.key(t))))
        throw new ApiException(
            HttpStatus.CONFLICT, "SCHEMA_MISMATCH", "Allowlist không khớp schema.");
      catalog.validateSemantic(
          sem,
          physical.stream()
              .filter(
                  t ->
                      allowed.stream()
                          .anyMatch(a -> CatalogService.key(a).equals(CatalogService.key(t))))
              .toList());
    }
    d.put("updatedAt", Instant.now().toString());
    store.updateDataset(d, sem);
    store.audit("DATASET_UPDATE", id);
    return d;
  }
}
