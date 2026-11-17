package dev.nla.registry;

import dev.nla.store.Store;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Semantic model of a dataset (T-3.1.2): the active business definitions (metrics, dimensions,
 * relationships, examples, business rules) together with the live physical tables and versions.
 */
@RestController
@RequestMapping("/api/v1")
public class SemanticModelController {
  private final Store store;
  private final CatalogService catalog;

  public SemanticModelController(Store store, CatalogService catalog) {
    this.store = store;
    this.catalog = catalog;
  }

  /** Any user allowed to read the dataset sees the full context; access is checked by Store. */
  @GetMapping("/datasets/{id}/semantic-model")
  Map<String, Object> semanticModel(@PathVariable String id) {
    var model = catalog.context(id);
    // The dataset record owns the active versions; the stored document may be older.
    var dataset = store.dataset(id);
    model.put("datasetId", id);
    model.put("semanticVersion", dataset.get("semanticVersion"));
    model.put("schemaVersion", dataset.get("schemaVersion"));
    return model;
  }
}
