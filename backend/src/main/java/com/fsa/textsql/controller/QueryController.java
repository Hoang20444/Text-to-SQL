package com.fsa.textsql.controller;

import com.fsa.textsql.dto.QueryRequest;
import com.fsa.textsql.dto.QueryResponse;
import com.fsa.textsql.repository.ChatHistoryRepository;
import com.fsa.textsql.service.AiService;
import com.fsa.textsql.service.QueryExecutionService;
import com.fsa.textsql.service.SqlGuardrailService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@RestController
@RequestMapping("/api/v1")
public class QueryController {

    private static final Logger log = LoggerFactory.getLogger(QueryController.class);

    private final AiService aiService;
    private final SqlGuardrailService sqlGuardrailService;
    private final QueryExecutionService queryExecutionService;
    private final ChatHistoryRepository chatHistoryRepository;

    // Standard constructor injection
    public QueryController(AiService aiService,
                           SqlGuardrailService sqlGuardrailService,
                           QueryExecutionService queryExecutionService,
                           ChatHistoryRepository chatHistoryRepository) {
        this.aiService = aiService;
        this.sqlGuardrailService = sqlGuardrailService;
        this.queryExecutionService = queryExecutionService;
        this.chatHistoryRepository = chatHistoryRepository;
    }

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

            return ResponseEntity.ok(QueryResponse.success(
                    request.getQuestion(),
                    validatedSql,
                    queryResult.columns(),
                    queryResult.rows(),
                    queryResult.suggestedChart(),
                    queryResult.rowCount(),
                    executionTime
            ));

        } catch (SecurityException se) {
            long executionTime = System.currentTimeMillis() - startTime;
            chatHistoryRepository.saveLog(request.getSessionId(), request.getQuestion(), generatedSql, "GUARDRAIL_BLOCKED", se.getMessage(), 0, executionTime);
            return ResponseEntity.badRequest().body(QueryResponse.blocked(
                    request.getQuestion(),
                    generatedSql,
                    "Guardrail chặn câu lệnh: " + se.getMessage(),
                    executionTime
            ));
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Query processing failed", e);
            chatHistoryRepository.saveLog(request.getSessionId(), request.getQuestion(), generatedSql, "ERROR", e.getMessage(), 0, executionTime);
            return ResponseEntity.internalServerError().body(QueryResponse.error(
                    request.getQuestion(),
                    generatedSql,
                    "Lỗi xử lý truy vấn: " + e.getMessage(),
                    executionTime
            ));
        }
    }

    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> getRecentHistory(
            @RequestParam(defaultValue = "default-session") String sessionId,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(chatHistoryRepository.findRecentHistory(sessionId, limit));
    }
}
