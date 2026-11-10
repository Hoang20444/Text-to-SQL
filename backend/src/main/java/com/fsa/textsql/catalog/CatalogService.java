package com.fsa.textsql.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsa.textsql.access.WarehouseAccessService;
import com.fsa.textsql.common.ApiException;
import com.fsa.textsql.dataset.Dataset;
import com.fsa.textsql.dataset.DatasetRepository;
import com.fsa.textsql.dataset.TableRef;
import com.fsa.textsql.query.SemanticModel;
import com.fsa.textsql.security.CurrentUser;
import com.fsa.textsql.warehouse.Engine;
import com.fsa.textsql.warehouse.WarehouseConnection;
import com.fsa.textsql.warehouse.WarehouseRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Data Catalog of a dataset (T-2.1.1, US-2.1 AC3): the dataset's allowed tables from the latest
 * physical scan, enriched with the semantic model. Only authorized callers see a dataset.
 */
@Service
public class CatalogService {

  private final DatasetRepository datasets;
  private final WarehouseRepository warehouses;
  private final DiscoveryRunRepository runs;
  private final WarehouseAccessService access;
  private final ObjectMapper json;

  public CatalogService(
      DatasetRepository datasets,
      WarehouseRepository warehouses,
      DiscoveryRunRepository runs,
      WarehouseAccessService access,
      ObjectMapper json) {
    this.datasets = datasets;
    this.warehouses = warehouses;
    this.runs = runs;
    this.access = access;
    this.json = json;
  }

  /** Everything the catalog endpoints need for one dataset, loaded once per request. */
  record View(
      Dataset dataset,
      WarehouseConnection warehouse,
      List<TableMetadata> tables,
      List<Relationship> relationships,
      SemanticModel semantic) {}

  public View load(String datasetId, CurrentUser user) {
    Dataset dataset = datasets.findById(datasetId).orElse(null);
    if (dataset == null) {
      if (!user.isAdmin()) {
        throw ApiException.forbidden("Không có quyền truy cập tập dữ liệu này.", Map.of());
      }
      throw ApiException.notFound("Không tìm thấy tập dữ liệu.");
    }
    access.requireAccess(user, dataset.warehouseId());
    WarehouseConnection warehouse =
        warehouses
            .findConnection(dataset.warehouseId())
            .orElseThrow(() -> ApiException.notFound("Không tìm thấy kho dữ liệu."));
    SchemaSnapshot snapshot =
        runs.latestSnapshot(dataset.warehouseId())
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.CONFLICT,
                        "DATASET_UNAVAILABLE",
                        "Kho dữ liệu chưa được quét cấu trúc; hãy chạy 'Quét cấu trúc bảng'.",
                        false,
                        Map.of("reason", "NOT_DISCOVERED")));
    String rawModel = datasets.semanticModel(datasetId).orElse("{}");
    SemanticModel semantic =
        SemanticModel.isConfigured(rawModel) ? SemanticModel.parse(rawModel, json) : null;

    Map<TableRef, SchemaSnapshot.Table> physical = new LinkedHashMap<>();
    snapshot.tables().forEach(t -> physical.put(t.ref(), t));
    Set<TableRef> allowed = new LinkedHashSet<>(dataset.allowedTables());

    List<Relationship> relationships = new ArrayList<>();
    for (TableRef ref : allowed) {
      SchemaSnapshot.Table table = physical.get(ref);
      if (table == null) {
        continue;
      }
      for (SchemaSnapshot.ForeignKey fk : table.foreignKeys()) {
        if (allowed.contains(fk.to().table())) {
          relationships.add(
              new Relationship(
                  new Relationship.Endpoint(fk.from().table(), fk.from().column()),
                  new Relationship.Endpoint(fk.to().table(), fk.to().column()),
                  "MANY_TO_ONE",
                  "FOREIGN_KEY"));
        }
      }
    }
    if (semantic != null) {
      for (Map<String, Object> declared : semantic.relationships()) {
        Relationship relation = semanticRelationship(declared);
        if (relation != null
            && allowed.contains(relation.from().table())
            && allowed.contains(relation.to().table())) {
          relationships.add(relation);
        }
      }
    }

    List<TableMetadata> tables = new ArrayList<>();
    for (TableRef ref : allowed) {
      SchemaSnapshot.Table table = physical.get(ref);
      List<Relationship> outgoing =
          relationships.stream()
              .filter(r -> r.from().table().equals(ref) && r.source().equals("FOREIGN_KEY"))
              .toList();
      tables.add(
          table == null
              ? new TableMetadata(
                  tableId(ref),
                  ref.schema(),
                  ref.name(),
                  "TABLE",
                  "",
                  ref.schema(),
                  "MISSING",
                  false,
                  List.of(),
                  List.of(),
                  List.of())
              : new TableMetadata(
                  tableId(ref),
                  ref.schema(),
                  ref.name(),
                  table.type(),
                  "",
                  ref.schema(),
                  "AVAILABLE",
                  true,
                  table.columns().stream()
                      .map(
                          c ->
                              new TableMetadata.Column(
                                  c.name(),
                                  c.dataType(),
                                  c.nativeType(),
                                  c.nullable(),
                                  "",
                                  List.of()))
                      .toList(),
                  table.primaryKey(),
                  outgoing));
    }
    return new View(dataset, warehouse, tables, relationships, semantic);
  }

  public CatalogSummary summary(String datasetId, CurrentUser user) {
    View view = load(datasetId, user);
    Engine engine = view.warehouse().engine();
    List<Map<String, Object>> metrics =
        view.semantic() == null ? List.of() : view.semantic().metrics();
    List<Map<String, Object>> dimensions =
        view.semantic() == null ? List.of() : view.semantic().dimensions();
    List<Map<String, Object>> examples =
        view.semantic() == null ? List.of() : view.semantic().examplesFor(engine);
    return new CatalogSummary(
        datasetId,
        view.dataset().semanticVersion(),
        view.dataset().schemaVersion(),
        (int) view.tables().stream().filter(TableMetadata::queryable).count(),
        metrics.size(),
        dimensions.size(),
        view.relationships().size(),
        examples.size(),
        categories(view.tables(), TableMetadata::category),
        categories(metrics, m -> (String) m.get("category")),
        categories(examples, e -> (String) e.get("category")));
  }

  /** Opaque, stable table id derived from the qualified name (same table => same id). */
  static String tableId(TableRef ref) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest((ref.schema() + "." + ref.name()).getBytes(StandardCharsets.UTF_8));
      return "tbl-" + HexFormat.of().formatHex(hash, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static <T> List<CatalogSummary.Category> categories(
      List<T> items, Function<T, String> category) {
    TreeMap<String, Long> counts = new TreeMap<>();
    items.stream()
        .map(category)
        .filter(Objects::nonNull)
        .filter(name -> !name.isBlank())
        .forEach(name -> counts.merge(name, 1L, Long::sum));
    return counts.entrySet().stream()
        .map(e -> new CatalogSummary.Category(e.getKey(), e.getValue()))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private static Relationship semanticRelationship(Map<String, Object> declared) {
    if (!(declared.get("from") instanceof Map<?, ?> from)
        || !(declared.get("to") instanceof Map<?, ?> to)) {
      return null;
    }
    Relationship.Endpoint start = endpoint((Map<String, Object>) from);
    Relationship.Endpoint end = endpoint((Map<String, Object>) to);
    if (start == null || end == null) {
      return null;
    }
    Object cardinality = declared.get("cardinality");
    return new Relationship(start, end, cardinality instanceof String s ? s : null, "SEMANTIC");
  }

  private static Relationship.Endpoint endpoint(Map<String, Object> raw) {
    if (!(raw.get("table") instanceof Map<?, ?> table)
        || !(table.get("schema") instanceof String schema)
        || !(table.get("name") instanceof String name)
        || !(raw.get("column") instanceof String column)) {
      return null;
    }
    return new Relationship.Endpoint(new TableRef(schema, name), column);
  }
}
