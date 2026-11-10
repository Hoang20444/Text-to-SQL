package com.fsa.textsql.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsa.textsql.access.WarehouseAccessService;
import com.fsa.textsql.common.ApiException;
import com.fsa.textsql.common.CorrelationIdFilter;
import com.fsa.textsql.dataset.Dataset;
import com.fsa.textsql.dataset.DatasetRepository;
import com.fsa.textsql.dataset.DatasetStatus;
import com.fsa.textsql.dataset.TableRef;
import com.fsa.textsql.security.AppRole;
import com.fsa.textsql.security.CurrentUser;
import com.fsa.textsql.warehouse.WarehouseConnection;
import com.fsa.textsql.warehouse.WarehouseRepository;
import com.fsa.textsql.warehouse.WarehouseStatus;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one question (T-1.3.2): authorize the warehouse, build the allowed semantic context,
 * ask the Python service for a candidate SQL, check it, run it read-only on the selected engine,
 * normalize the result and record basic history. Python only proposes SQL; Java decides.
 */
@Service
public class QueryService {

  private static final Logger log = LoggerFactory.getLogger(QueryService.class);
  private static final Set<String> MODES = Set.of("AUTO", "API", "DETERMINISTIC");

  private final WarehouseAccessService access;
  private final WarehouseRepository warehouses;
  private final DatasetRepository datasets;
  private final WarehouseConnector connector;
  private final TableMetadataReader metadata;
  private final AiClient ai;
  private final QueryHistoryRepository history;
  private final QueryProperties limits;
  private final ObjectMapper json;

  public QueryService(
      WarehouseAccessService access,
      WarehouseRepository warehouses,
      DatasetRepository datasets,
      WarehouseConnector connector,
      TableMetadataReader metadata,
      AiClient ai,
      QueryHistoryRepository history,
      QueryProperties limits,
      ObjectMapper json) {
    this.access = access;
    this.warehouses = warehouses;
    this.datasets = datasets;
    this.connector = connector;
    this.metadata = metadata;
    this.ai = ai;
    this.history = history;
    this.limits = limits;
    this.json = json;
  }

  public QueryResponse run(QueryRequest request, CurrentUser user) {
    // ---- Preconditions: no history row is created when these fail ----
    if (request == null) {
      throw ApiException.invalidRequest("Thiếu nội dung yêu cầu.");
    }
    String warehouseId = required(request.warehouseId(), "warehouseId");
    String datasetId = required(request.datasetId(), "datasetId");
    String question = required(request.question(), "question").strip();
    if (question.codePointCount(0, question.length()) > 1000) {
      throw ApiException.invalidRequest(
          "Câu hỏi tối đa 1000 ký tự.", Map.of("parameter", "question"));
    }
    String mode = request.mode() == null || request.mode().isBlank() ? "AUTO" : request.mode();
    if (!MODES.contains(mode)) {
      throw ApiException.invalidRequest(
          "mode không hợp lệ.", Map.of("parameter", "mode", "allowed", List.copyOf(MODES)));
    }
    if (request.providerId() != null) {
      throw ApiException.invalidRequest(
          "Chưa hỗ trợ chọn providerId; quản lý model provider thuộc Sprint 2.",
          Map.of("parameter", "providerId"));
    }
    if (!user.isAdmin() && !user.roles().contains(AppRole.ANALYST)) {
      throw ApiException.forbidden(
          "Vai trò hiện tại chỉ được xem, không được chạy truy vấn.", Map.of());
    }
    access.requireAccess(user, warehouseId);

    WarehouseConnection warehouse =
        warehouses
            .findConnection(warehouseId)
            .orElseThrow(() -> ApiException.notFound("Không tìm thấy kho dữ liệu."));
    Dataset dataset =
        datasets
            .findById(datasetId)
            .orElseThrow(() -> ApiException.notFound("Không tìm thấy tập dữ liệu."));
    if (!dataset.warehouseId().equals(warehouseId)) {
      throw ApiException.invalidRequest(
          "Tập dữ liệu không thuộc kho dữ liệu đã chọn.", Map.of("datasetId", datasetId));
    }
    if (warehouse.status() != WarehouseStatus.ACTIVE || dataset.status() != DatasetStatus.ACTIVE) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "DATASET_UNAVAILABLE",
          "Kho dữ liệu hoặc tập dữ liệu đang không hoạt động.",
          false,
          Map.of());
    }
    SemanticModel semantic =
        SemanticModel.parse(datasets.semanticModel(datasetId).orElse("{}"), json);

    // ---- From here on the query exists in history ----
    String queryId = "qry-" + UUID.randomUUID();
    String correlationId = CorrelationIdFilter.current();
    String executionMode = mode.equals("DETERMINISTIC") ? "DETERMINISTIC" : "API";
    Instant createdAt = Instant.now();
    long start = System.nanoTime();
    history.insertRunning(
        new QueryHistoryRepository.Started(
            queryId,
            correlationId,
            user.subject(),
            warehouseId,
            datasetId,
            warehouse.engine().name(),
            question,
            executionMode,
            dataset.semanticVersion(),
            dataset.schemaVersion(),
            createdAt));

    AiCandidate candidate = null;
    String generatedSql = null;
    try (Connection connection = connector.openReadOnly(warehouse)) {
      List<Map<String, Object>> tables =
          metadata.read(
              connection, warehouse.engine(), warehouse.database(), dataset.allowedTables());
      Map<String, Object> aiRequest =
          aiRequest(correlationId, warehouse, dataset, question, semantic, tables);
      candidate = ai.generate(aiRequest, correlationId, mode);
      generatedSql = ReadOnlySqlCheck.requireSingleSelect(candidate.sql());

      QueryResult result;
      try (Statement statement = connection.createStatement()) {
        statement.setQueryTimeout(limits.statementTimeoutSeconds());
        statement.setMaxRows(limits.maxRows() + 1); // one extra row reveals truncation
        try (ResultSet rs = statement.executeQuery(generatedSql)) {
          result =
              ResultReader.read(
                  rs,
                  limits.maxRows(),
                  semantic.timezone(),
                  candidate.selectedMetrics(),
                  candidate.selectedDimensions());
        }
      }
      long latency = elapsedMs(start);
      Instant completedAt = Instant.now();
      history.complete(
          queryId,
          new QueryHistoryRepository.Completed(
              "SUCCESS",
              candidate.model(),
              generatedSql,
              result.returnedRowCount(),
              result.truncated(),
              latency,
              null,
              completedAt));
      return new QueryResponse(
          queryId,
          correlationId,
          warehouseId,
          datasetId,
          warehouse.engine(),
          question,
          "SUCCESS",
          candidate.sql(),
          generatedSql,
          warehouse.engine(),
          candidate.model(),
          null,
          executionMode,
          dataset.semanticVersion(),
          dataset.schemaVersion(),
          latency,
          createdAt,
          completedAt,
          candidate.explanation(),
          candidate.assumptions(),
          candidate.selectedMetrics(),
          candidate.selectedDimensions(),
          sourceTables(generatedSql, dataset.allowedTables()),
          result,
          chart(candidate.chartRecommendation(), result),
          null);
    } catch (ApiException e) {
      throw fail(queryId, start, candidate, generatedSql, e);
    } catch (SQLTimeoutException e) {
      throw fail(
          queryId,
          start,
          candidate,
          generatedSql,
          new ApiException(
              HttpStatus.GATEWAY_TIMEOUT,
              "QUERY_TIMEOUT",
              "Truy vấn vượt quá thời gian cho phép.",
              true,
              Map.of()));
    } catch (SQLException e) {
      log.warn(
          "Query execution failed queryId={} engine={} sqlState={} vendorCode={}",
          queryId,
          warehouse.engine(),
          e.getSQLState(),
          e.getErrorCode());
      throw fail(
          queryId,
          start,
          candidate,
          generatedSql,
          new ApiException(
              HttpStatus.INTERNAL_SERVER_ERROR,
              "QUERY_ERROR",
              "Kho dữ liệu không thực thi được truy vấn.",
              false,
              Map.of()));
    }
  }

  private Map<String, Object> aiRequest(
      String correlationId,
      WarehouseConnection warehouse,
      Dataset dataset,
      String question,
      SemanticModel semantic,
      List<Map<String, Object>> tables) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("tables", tables);
    context.put("metrics", semantic.metrics());
    context.put("dimensions", semantic.dimensions());
    context.put("relationships", semantic.relationships());
    context.put("examples", semantic.examplesFor(warehouse.engine()));
    context.put("businessRules", semantic.businessRules());
    context.put("semanticVersion", dataset.semanticVersion());
    context.put("schemaVersion", dataset.schemaVersion());
    context.put("timezone", semantic.timezone().getId());
    context.put("referenceTime", Instant.now().toString());

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("correlationId", correlationId);
    body.put("warehouseId", warehouse.id());
    body.put("datasetId", dataset.datasetId());
    body.put("sqlDialect", warehouse.engine().name());
    body.put("question", question);
    body.put("semanticContext", context);
    return body;
  }

  /** Records the failure in history and returns the error with the queryId attached. */
  private ApiException fail(
      String queryId, long start, AiCandidate candidate, String generatedSql, ApiException e) {
    String status = terminalStatus(e);
    history.complete(
        queryId,
        new QueryHistoryRepository.Completed(
            status,
            candidate == null ? null : candidate.model(),
            generatedSql != null ? generatedSql : candidate == null ? null : candidate.sql(),
            null,
            null,
            elapsedMs(start),
            e.code(),
            Instant.now()));
    Map<String, Object> details = new HashMap<>(e.details());
    details.put("queryId", queryId);
    details.put("status", status);
    return new ApiException(e.status(), e.code(), e.getMessage(), e.retryable(), details);
  }

  /** Query terminal status for an error code (docs/contracts/errors.md). */
  static String terminalStatus(ApiException e) {
    return switch (e.code()) {
      case "SQL_SECURITY_ERROR", "INSUFFICIENT_CONTEXT", "UNSUPPORTED_QUESTION", "LOW_CONFIDENCE" ->
          "REJECTED";
      case "QUERY_TIMEOUT" -> "TIMEOUT";
      case "INVALID_REQUEST",
              "DATASET_UNAVAILABLE",
              "SCHEMA_MISMATCH",
              "SEMANTIC_MODEL_INVALID",
              "SQL_VALIDATION_ERROR",
              "SQL_DIALECT_ERROR" ->
          "VALIDATION_ERROR";
      default -> "QUERY_ERROR";
    };
  }

  /** Keeps the AI chart only when every referenced column exists in the result. */
  static ChartSpec chart(ChartSpec recommended, QueryResult result) {
    Set<String> columns = new java.util.HashSet<>();
    result.columns().forEach(c -> columns.add(c.name()));
    boolean valid =
        recommended != null
            && (recommended.x() == null || columns.contains(recommended.x()))
            && recommended.series().stream().allMatch(s -> columns.contains(s.column()));
    return valid
        ? recommended
        : ChartSpec.table("Biểu đồ gợi ý tham chiếu cột không có trong kết quả.");
  }

  /** Allowed tables whose name appears as an identifier in the executed SQL (informational). */
  static List<TableRef> sourceTables(String sql, List<TableRef> allowed) {
    Set<String> identifiers = ReadOnlySqlCheck.identifiers(sql);
    return allowed.stream()
        .filter(t -> identifiers.contains(t.name().toLowerCase(java.util.Locale.ROOT)))
        .toList();
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) {
      throw ApiException.invalidRequest(name + " là bắt buộc.", Map.of("parameter", name));
    }
    return value;
  }
}
