package com.fsa.textsql.query;

import com.fsa.textsql.common.ErrorResponse;
import com.fsa.textsql.dataset.TableRef;
import com.fsa.textsql.warehouse.Engine;
import java.time.Instant;
import java.util.List;

/** Public Query detail (docs/contracts/domain.md). */
public record QueryResponse(
    String queryId,
    String correlationId,
    String warehouseId,
    String datasetId,
    Engine engine,
    String question,
    String status,
    String candidateSql,
    String generatedSql,
    Engine sqlDialect,
    String model,
    Object provider,
    String executionMode,
    String semanticVersion,
    String schemaVersion,
    Long latencyMs,
    Instant createdAt,
    Instant completedAt,
    String explanation,
    List<String> assumptions,
    List<String> selectedMetrics,
    List<String> selectedDimensions,
    List<TableRef> sourceTables,
    QueryResult result,
    ChartSpec chart,
    ErrorResponse.ErrorBody error) {}
