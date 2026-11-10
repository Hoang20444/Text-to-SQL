package dev.nla;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;

/** Validates persisted semantic definitions against the authorized physical dataset. */
final class SemanticModelSupport {
  private SemanticModelSupport() {}

  static void validateDocument(
      String datasetId,
      Map<String, Object> document,
      Store store,
      CatalogService catalog,
      SqlGuard guard) {
    RegistryController.fields(
        document,
        Set.of(
            "tables",
            "metrics",
            "dimensions",
            "relationships",
            "examples",
            "businessRules",
            "semanticVersion",
            "schemaVersion",
            "timezone",
            "referenceTime"));
    RegistryController.required(document, "semanticVersion", "schemaVersion", "timezone");
    if (Json.write(document).length() > 1_000_000) {
      throw ApiException.bad("Cấu hình ngữ nghĩa vượt giới hạn.");
    }
    try {
      java.time.ZoneId.of(Json.str(document, "timezone"));
    } catch (Exception exception) {
      throw ApiException.bad("Timezone không hợp lệ.");
    }

    var dataset = store.dataset(datasetId);
    if (!Objects.equals(dataset.get("schemaVersion"), document.get("schemaVersion"))) {
      throw new ApiException(
          HttpStatus.CONFLICT, "SCHEMA_MISMATCH", "Schema version phải khớp dataset hiện tại.");
    }

    var physicalTables = catalog.tables(datasetId);
    var context = new LinkedHashMap<>(document);
    context.put("tables", physicalTables);
    catalog.validateSemantic(context, physicalTables);
    catalog.validateJoinCardinality(datasetId, context);
    context.put("referenceTime", Instant.now().toString());

    var warehouse = store.warehouse(Json.str(dataset, "warehouseId"));
    String engine = Json.str(warehouse, "engine");
    for (var example : Json.objects(context.get("examples"))) {
      if (!engine.equals(Json.str(example, "sqlDialect"))) {
        continue;
      }
      guard.validate(Json.str(example, "sql"), engine, physicalTables);
    }
  }
}
