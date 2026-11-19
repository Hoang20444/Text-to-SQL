package dev.nla.registry;

import dev.nla.ai.AiClient;
import dev.nla.common.ApiException;
import dev.nla.common.Errors;
import dev.nla.common.Json;
import dev.nla.common.Requests;
import dev.nla.modelprovider.ModelProviderService;
import dev.nla.store.Store;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Gợi ý bằng AI" for the semantic workbench (T-3.2.2, US-3.2). The AI reads the live table
 * structure and proposes metrics, dimensions and business rules; Java checks each proposal again
 * and returns a preview. Nothing is saved: the admin reviews, edits and saves with PUT
 * semantic-model under a new version. When the AI is down the manual editor is unaffected.
 */
@RestController
@RequestMapping("/api/v1")
public class SemanticAssistController {
  static final String DEFAULT_INSTRUCTION =
      "Đề xuất các chỉ số kinh doanh, chiều phân tích và từ đồng nghĩa tiếng Việt/Anh dễ hiểu"
          + " từ cấu trúc bảng hiện có (cột tiền tệ, số lượng, ngày tháng, danh mục).";

  private final Store store;
  private final CatalogService catalog;
  private final AiClient ai;
  private final ModelProviderService providers;
  private final SemanticDrafts drafts;

  public SemanticAssistController(
      Store store,
      CatalogService catalog,
      AiClient ai,
      ModelProviderService providers,
      SemanticDrafts drafts) {
    this.store = store;
    this.catalog = catalog;
    this.ai = ai;
    this.providers = providers;
    this.drafts = drafts;
  }

  @PostMapping("/datasets/{id}/semantic-assist")
  @PreAuthorize("hasRole('ADMIN')")
  Map<String, Object> assist(@PathVariable String id, @RequestBody Map<String, Object> body) {
    Requests.fields(body, Set.of("instruction", "providerId"));
    String instruction = Json.str(body, "instruction").strip();
    if (instruction.isEmpty()) instruction = DEFAULT_INSTRUCTION;
    if (instruction.length() > 2000) throw ApiException.bad("instruction tối đa 2000 ký tự.");
    if (body.containsKey("providerId")
        && (!(body.get("providerId") instanceof String providerId)
            || providerId.isBlank()
            || providerId.length() > 100)) throw ApiException.bad("providerId không hợp lệ.");

    var dataset = store.dataset(id);
    var warehouse = store.warehouse(Json.str(dataset, "warehouseId"));
    if (!"ACTIVE".equals(dataset.get("status")) || !"ACTIVE".equals(warehouse.get("status")))
      throw new ApiException(
          HttpStatus.CONFLICT, "DATASET_UNAVAILABLE", "Warehouse hoặc dataset không hoạt động.");
    String engine = Json.str(warehouse, "engine");
    var context = catalog.context(id);
    context.put("semanticVersion", dataset.get("semanticVersion"));
    context.put("schemaVersion", dataset.get("schemaVersion"));
    // Only metadata goes to the model: no sample rows and no SQL examples.
    var semanticContext = new java.util.LinkedHashMap<>(context);
    semanticContext.put("examples", List.of());

    var request =
        Json.obj(
            "correlationId",
            Errors.correlation(),
            "warehouseId",
            warehouse.get("warehouseId"),
            "datasetId",
            id,
            "sqlDialect",
            engine,
            "semanticContext",
            semanticContext,
            "instruction",
            instruction);
    String providerId = Json.str(body, "providerId");
    var provider = providerId.isBlank() ? providers.selectDefault() : providers.select(providerId);
    provider.apply(request);

    var preview = drafts.preview(engine, context, catalog.tables(id), ai.semanticAssist(request));
    preview.put("datasetId", id);
    preview.put("provider", provider.snapshot());
    store.audit("SEMANTIC_ASSIST", id, Json.obj("model", preview.get("model")));
    return preview;
  }
}
