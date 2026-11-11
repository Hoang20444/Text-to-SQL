package dev.nla.registry;

import dev.nla.common.ApiException;
import dev.nla.common.Page;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Data Catalog of a dataset: summary, tables, relationships and semantic collections (T-2.1.1). */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {
  private final CatalogService catalog;

  public CatalogController(CatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/datasets/{id}/catalog")
  Object catalog(@PathVariable String id) {
    return catalog.summary(id);
  }

  @GetMapping("/datasets/{id}/{kind:tables|metrics|dimensions|relationships|examples}")
  Object collection(
      @PathVariable String id, @PathVariable String kind, @RequestParam Map<String, String> p) {
    return Page.of(
        catalog.collection(id, kind),
        p,
        "name",
        Set.of("name", "question", "createdAt", "updatedAt"));
  }

  @GetMapping("/datasets/{id}/{kind:tables|metrics|dimensions}/{itemId}")
  Object detail(@PathVariable String id, @PathVariable String kind, @PathVariable String itemId) {
    return catalog.collection(id, kind).stream()
        .filter(x -> itemId.equals(x.get(kind.equals("tables") ? "tableId" : "name")))
        .findFirst()
        .orElseThrow(ApiException::notFound);
  }
}
