package com.fsa.textsql.warehouse;

import com.fsa.textsql.access.WarehouseAccessService;
import com.fsa.textsql.common.ApiException;
import com.fsa.textsql.query.QueryProperties;
import com.fsa.textsql.security.CurrentUser;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Registration and detail of warehouses (T-2.1.1). */
@Service
public class WarehouseService {

  private static final Pattern CREDENTIAL_REFERENCE = Pattern.compile("env:[A-Z][A-Z0-9_]{0,127}");
  private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.\\-]{1,255}");

  private final WarehouseRepository warehouses;
  private final WarehouseAccessService access;
  private final QueryProperties limits;

  public WarehouseService(
      WarehouseRepository warehouses, WarehouseAccessService access, QueryProperties limits) {
    this.warehouses = warehouses;
    this.access = access;
    this.limits = limits;
  }

  /**
   * Registers a warehouse as UNAVAILABLE: it becomes ACTIVE only after a successful connection
   * test, so the status always reflects a real check.
   */
  public WarehouseDetail register(WarehouseCreateRequest request, CurrentUser user) {
    requireAdmin(user);
    if (request == null) {
      throw ApiException.invalidRequest("Thiếu nội dung yêu cầu.");
    }
    String name = text(request.name(), "name", 200);
    Engine engine = engine(request.engine());
    String host = text(request.host(), "host", 255);
    if (!HOST.matcher(host).matches()) {
      throw ApiException.invalidRequest("host không hợp lệ.", Map.of("parameter", "host"));
    }
    if (!limits.allowedHostSet().isEmpty() && !limits.allowedHostSet().contains(host)) {
      throw ApiException.invalidRequest(
          "host không nằm trong danh sách được phép kết nối.", Map.of("parameter", "host"));
    }
    if (request.port() == null || request.port() < 1 || request.port() > 65535) {
      throw ApiException.invalidRequest("port phải từ 1 đến 65535.", Map.of("parameter", "port"));
    }
    String database = text(request.database(), "database", 128);
    String defaultSchema =
        request.defaultSchema() == null || request.defaultSchema().isBlank()
            ? null
            : text(request.defaultSchema(), "defaultSchema", 128);
    String username = text(request.username(), "username", 128);
    String reference = text(request.credentialReference(), "credentialReference", 132);
    if (!CREDENTIAL_REFERENCE.matcher(reference).matches()) {
      throw ApiException.invalidRequest(
          "credentialReference phải có dạng env:TEN_BIEN (chữ in hoa, số, dấu _).",
          Map.of("parameter", "credentialReference"));
    }

    String id = "wh-" + UUID.randomUUID().toString().substring(0, 13);
    warehouses.insertIfAbsent(
        new WarehouseRegistration(
            id,
            name,
            engine,
            WarehouseStatus.UNAVAILABLE,
            host,
            request.port(),
            database,
            defaultSchema,
            username,
            reference));
    return warehouses.findDetail(id).orElseThrow();
  }

  /** Authorized detail; connection settings only for ADMIN. */
  public WarehouseDetail detail(String warehouseId, CurrentUser user) {
    access.requireAccess(user, warehouseId);
    WarehouseDetail detail =
        warehouses
            .findDetail(warehouseId)
            .orElseThrow(() -> ApiException.notFound("Không tìm thấy kho dữ liệu."));
    return user.isAdmin() ? detail : detail.withoutConnection();
  }

  static void requireAdmin(CurrentUser user) {
    if (!user.isAdmin()) {
      throw ApiException.forbidden("Chỉ quản trị viên được thực hiện thao tác này.", Map.of());
    }
  }

  private static Engine engine(String value) {
    try {
      return Engine.valueOf(value == null ? "" : value);
    } catch (IllegalArgumentException e) {
      throw ApiException.invalidRequest(
          "engine không hợp lệ.",
          Map.of(
              "parameter",
              "engine",
              "allowed",
              Arrays.stream(Engine.values()).map(Enum::name).toList()));
    }
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
