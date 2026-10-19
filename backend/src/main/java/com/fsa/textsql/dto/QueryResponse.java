package com.fsa.textsql.dto;

import java.util.List;
import java.util.Map;

public class QueryResponse {

    private String question;
    private String generatedSql;
    private List<String> columns;
    private List<Map<String, Object>> data;
    private String suggestedChart; // 'BAR', 'LINE', 'PIE', 'TABLE', 'METRIC'
    private long executionTimeMs;
    private int rowCount;
    private String status; // 'SUCCESS', 'GUARDRAIL_BLOCKED', 'ERROR'
    private String errorMessage;

    public QueryResponse() {
    }

    public QueryResponse(String question, String generatedSql, List<String> columns, 
                         List<Map<String, Object>> data, String suggestedChart, 
                         long executionTimeMs, int rowCount, String status, String errorMessage) {
        this.question = question;
        this.generatedSql = generatedSql;
        this.columns = columns;
        this.data = data;
        this.suggestedChart = suggestedChart;
        this.executionTimeMs = executionTimeMs;
        this.rowCount = rowCount;
        this.status = status;
        this.errorMessage = errorMessage;
    }

    public static QueryResponse success(String question, String generatedSql, List<String> columns,
                                        List<Map<String, Object>> data, String suggestedChart,
                                        int rowCount, long executionTimeMs) {
        return new QueryResponse(question, generatedSql, columns, data, suggestedChart, executionTimeMs, rowCount, "SUCCESS", null);
    }

    public static QueryResponse blocked(String question, String generatedSql, String errorMessage, long executionTimeMs) {
        return new QueryResponse(question, generatedSql, null, null, null, executionTimeMs, 0, "GUARDRAIL_BLOCKED", errorMessage);
    }

    public static QueryResponse error(String question, String generatedSql, String errorMessage, long executionTimeMs) {
        return new QueryResponse(question, generatedSql, null, null, null, executionTimeMs, 0, "ERROR", errorMessage);
    }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getGeneratedSql() { return generatedSql; }
    public void setGeneratedSql(String generatedSql) { this.generatedSql = generatedSql; }

    public List<String> getColumns() { return columns; }
    public void setColumns(List<String> columns) { this.columns = columns; }

    public List<Map<String, Object>> getData() { return data; }
    public void setData(List<Map<String, Object>> data) { this.data = data; }

    public String getSuggestedChart() { return suggestedChart; }
    public void setSuggestedChart(String suggestedChart) { this.suggestedChart = suggestedChart; }

    public long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(long executionTimeMs) { this.executionTimeMs = executionTimeMs; }

    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
