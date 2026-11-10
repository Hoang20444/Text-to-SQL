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
  private final com.fsa.textsql.query.WarehouseConnector connector;

  public WarehouseService(
      WarehouseRepository warehouses,
      WarehouseAccessService access,
      QueryProperties limits,
      com.fsa.textsql.query.WarehouseConnector connector) {
    this.warehouses = warehouses;
    this.access = access;
    this.limits = limits;
    this.connector = connector;
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

  /**
   * Opens a read-only connection with the reader account and runs a trivial query. Success marks
   * the warehouse ACTIVE, failure UNAVAILABLE (DISABLED stays DISABLED). Errors are sanitized.
   */
  public ConnectionTestResult testConnection(String warehouseId, CurrentUser user) {
    requireAdmin(user);
    WarehouseConnection warehouse =
        warehouses
            .findConnection(warehouseId)
            .orElseThrow(() -> ApiException.notFound("Không tìm thấy kho dữ liệu."));
    long start = System.nanoTime();
    try (java.sql.Connection connection = connector.openReadOnly(warehouse);
        java.sql.Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(limits.loginTimeoutSeconds());
      statement.execute("SELECT 1");
      long latency = (System.nanoTime() - start) / 1_000_000;
      if (warehouse.status() != WarehouseStatus.DISABLED) {
        warehouses.updateStatus(warehouseId, WarehouseStatus.ACTIVE);
      }
      return new ConnectionTestResult(warehouseId, true, latency, java.time.Instant.now(), null);
    } catch (ApiException e) {
      return failed(warehouse, e);
    } catch (java.sql.SQLException e) {
      return failed(
          warehouse,
          new ApiException(
              org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
              "WAREHOUSE_CONNECTION_ERROR",
              "Kết nối được nhưng không chạy được truy vấn kiểm tra.",
              true,
              Map.of("reason", "CHECK_QUERY_FAILED")));
    }
  }

  private ConnectionTestResult failed(WarehouseConnection warehouse, ApiException e) {
    if (warehouse.status() != WarehouseStatus.DISABLED) {
      warehouses.updateStatus(warehouse.id(), WarehouseStatus.UNAVAILABLE);
    }
    return new ConnectionTestResult(
        warehouse.id(),
        false,
        null,
        java.time.Instant.now(),
        com.fsa.textsql.common.ErrorResponse.of(
                e.code(), e.getMessage(), e.retryable(), e.details())
            .error());
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
