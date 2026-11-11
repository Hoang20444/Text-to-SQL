package dev.nla;

import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Warehouse and dataset registry read APIs (T-1.1.1). */
@RestController
@RequestMapping("/api/v1")
public class RegistryController {
  private final Store store;

  public RegistryController(Store store) {
    this.store = store;
  }

  static void fields(Map<String, Object> body, Set<String> allowed) {
    if (!allowed.containsAll(body.keySet()))
      throw ApiException.bad("Yêu cầu chứa trường không được hỗ trợ.");
  }

  static void required(Map<String, Object> body, String... fields) {
    for (String f : fields)
      if (Json.str(body, f).isBlank() || Json.str(body, f).length() > 2000)
        throw ApiException.bad("Thiếu hoặc sai trường " + f);
  }

  /** Connection details are visible to ADMIN only; secrets are never stored in documents. */
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
}
