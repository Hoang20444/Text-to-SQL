package com.fsa.textsql.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryResponse {

    private String question;
    private String generatedSql;
    private List<String> columns;
    private List<Map<String, Object>> data;
    private String suggestedChart; // 'BAR', 'LINE', 'PIE', 'TABLE', 'METRIC'
    private long executionTimeMs;
    private int rowCount;
    private String status; // 'SUCCESS', 'GUARDRAIL_BLOCKED', 'SYNTAX_ERROR', 'AI_ERROR'
    private String errorMessage;
}
