package com.fsa.textsql.controller;

import com.fsa.textsql.dto.QueryRequest;
import com.fsa.textsql.dto.QueryResponse;
import com.fsa.textsql.repository.ChatHistoryRepository;
import com.fsa.textsql.service.AiService;
import com.fsa.textsql.service.QueryExecutionService;
import com.fsa.textsql.service.SqlGuardrailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Main Controller handling natural language query execution flow.
 * Shared pipeline integrating:
 * - Dev 3: AiService
 * - Dev 2: SqlGuardrailService & QueryExecutionService & ChatHistoryRepository
 * - Dev 1: Frontend contract
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class QueryController {

    private final AiService aiService;
    private final SqlGuardrailService sqlGuardrailService;
    private final QueryExecutionService queryExecutionService;
    private final ChatHistoryRepository chatHistoryRepository;

    @PostMapping("/query")
    public ResponseEntity<QueryResponse> processQuery(@Valid @RequestBody QueryRequest request) {
        long startTime = System.currentTimeMillis();
        String generatedSql = null;
        String validatedSql = null;

        try {
            // Step 1: LLM generates SQL (Dev 3)
            generatedSql = aiService.generateSql(request.getQuestion());

            // Step 2: Guardrail validation & limit enforcement (Dev 2)
            validatedSql = sqlGuardrailService.validateAndEnforce(generatedSql);

            // Step 3: Execute query on Chinook Warehouse (Dev 2)
            QueryExecutionService.QueryResult queryResult = queryExecutionService.executeQuery(validatedSql);
            long executionTime = System.currentTimeMillis() - startTime;

            // Step 4: Save audit log
            chatHistoryRepository.saveLog(
                    request.getSessionId(),
                    request.getQuestion(),
                    validatedSql,
                    "SUCCESS",
                    null,
                    queryResult.rowCount(),
                    executionTime
            );

            return ResponseEntity.ok(QueryResponse.builder()
                    .question(request.getQuestion())
                    .generatedSql(validatedSql)
                    .columns(queryResult.columns())
                    .data(queryResult.rows())
                    .suggestedChart(queryResult.suggestedChart())
                    .rowCount(queryResult.rowCount())
                    .executionTimeMs(executionTime)
                    .status("SUCCESS")
                    .build());

        } catch (SecurityException se) {
            long executionTime = System.currentTimeMillis() - startTime;
            chatHistoryRepository.saveLog(request.getSessionId(), request.getQuestion(), generatedSql, "GUARDRAIL_BLOCKED", se.getMessage(), 0, executionTime);
            return ResponseEntity.badRequest().body(QueryResponse.builder()
                    .question(request.getQuestion())
                    .generatedSql(generatedSql)
                    .status("GUARDRAIL_BLOCKED")
                    .errorMessage("Guardrail chặn câu lệnh: " + se.getMessage())
                    .executionTimeMs(executionTime)
                    .build());
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            chatHistoryRepository.saveLog(request.getSessionId(), request.getQuestion(), generatedSql, "ERROR", e.getMessage(), 0, executionTime);
            return ResponseEntity.internalServerError().body(QueryResponse.builder()
                    .question(request.getQuestion())
                    .generatedSql(generatedSql)
                    .status("ERROR")
                    .errorMessage("Lỗi xử lý truy vấn: " + e.getMessage())
                    .executionTimeMs(executionTime)
                    .build());
        }
    }

    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> getRecentHistory(
            @RequestParam(defaultValue = "default-session") String sessionId,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(chatHistoryRepository.findRecentHistory(sessionId, limit));
    }
}
