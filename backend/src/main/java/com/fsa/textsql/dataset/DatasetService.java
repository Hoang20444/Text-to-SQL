package com.fsa.textsql.dataset;

import com.fsa.textsql.catalog.DiscoveryRunRepository;
import com.fsa.textsql.catalog.SchemaSnapshot;
import com.fsa.textsql.common.ApiException;
import com.fsa.textsql.security.CurrentUser;
import com.fsa.textsql.warehouse.WarehouseDetail;
import com.fsa.textsql.warehouse.WarehouseRepository;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Creates datasets on scanned warehouses (T-2.1.1). */
@Service
public class DatasetService {

  /** semanticVersion of a dataset that has no semantic model yet (Semantic Layer is Sprint 3). */
  static final String NO_SEMANTIC_MODEL = "0.0.0";

  private final DatasetRepository datasets;
  private final WarehouseRepository warehouses;
  private final DiscoveryRunRepository runs;

  public DatasetService(
      DatasetRepository datasets, WarehouseRepository warehouses, DiscoveryRunRepository runs) {
    this.datasets = datasets;
    this.warehouses = warehouses;
    this.runs = runs;
  }

  public Dataset create(DatasetCreateRequest request, CurrentUser user) {
    if (!user.isAdmin()) {
      throw ApiException.forbidden("Chỉ quản trị viên được tạo tập dữ liệu.", Map.of());
    }
    if (request == null) {
      throw ApiException.invalidRequest("Thiếu nội dung yêu cầu.");
    }
    String warehouseId = text(request.warehouseId(), "warehouseId", 100);
    String name = text(request.name(), "name", 200);
    String description = request.description() == null ? "" : request.description().strip();
    if (description.length() > 2000) {
      throw ApiException.invalidRequest(
          "description tối đa 2000 ký tự.", Map.of("parameter", "description"));
    }
    List<TableRef> tables = request.allowedTables() == null ? List.of() : request.allowedTables();
    if (tables.isEmpty() || tables.size() > 500) {
      throw ApiException.invalidRequest(
          "allowedTables cần từ 1 đến 500 bảng.", Map.of("parameter", "allowedTables"));
    }
    for (TableRef table : tables) {
      if (table == null
          || table.schema() == null
          || table.schema().isBlank()
          || table.name() == null
          || table.name().isBlank()) {
        throw ApiException.invalidRequest(
            "Mỗi bảng phải ghi đủ schema và name.", Map.of("parameter", "allowedTables"));
      }
    }
    Set<String> schemas =
        new LinkedHashSet<>(
            request.allowedSchemas() == null || request.allowedSchemas().isEmpty()
                ? tables.stream().map(TableRef::schema).toList()
                : request.allowedSchemas());
    if (tables.stream().anyMatch(t -> !schemas.contains(t.schema()))) {
      throw ApiException.invalidRequest(
          "Có bảng nằm ngoài allowedSchemas.", Map.of("parameter", "allowedTables"));
    }

    WarehouseDetail warehouse =
        warehouses
            .findDetail(warehouseId)
            .orElseThrow(() -> ApiException.notFound("Không tìm thấy kho dữ liệu."));
    SchemaSnapshot snapshot =
        runs.latestSnapshot(warehouseId)
            .orElseThrow(
                () ->
                    new ApiException(
                        HttpStatus.CONFLICT,
                        "DATASET_UNAVAILABLE",
                        "Kho dữ liệu chưa được quét cấu trúc; hãy quét trước khi tạo tập dữ liệu.",
                        false,
                        Map.of("reason", "NOT_DISCOVERED")));
    Set<TableRef> present = new HashSet<>();
    snapshot.tables().forEach(t -> present.add(t.ref()));
    List<String> missing =
        tables.stream()
            .filter(t -> !present.contains(t))
            .map(t -> t.schema() + "." + t.name())
            .toList();
    if (!missing.isEmpty()) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "SCHEMA_MISMATCH",
          "Có bảng không tồn tại trong lượt quét gần nhất.",
          false,
          Map.of("missingTables", missing));
    }

    String id = "ds-" + UUID.randomUUID().toString().substring(0, 13);
    datasets.insertIfAbsent(
        new DatasetRegistration(
            id,
            warehouseId,
            name,
            description,
            DatasetStatus.ACTIVE,
            List.copyOf(schemas),
            List.copyOf(new LinkedHashSet<>(tables)),
            NO_SEMANTIC_MODEL,
            warehouse.schemaVersion()));
    return datasets.findById(id).orElseThrow();
  }

  private static String text(String value, String name, int max) {
    if (value == null || value.isBlank()) {
      throw ApiException.invalidRequest(name + " là bắt buộc.", Map.of("parameter", name));
    }
    String trimmed = value.strip();
    if (trimmed.length() > max) {
      throw ApiException.invalidRequest(
          name + " tối đa " + max + " ký tự.", Map.of("parameter", name));
    }
    return trimmed;
  }
}
