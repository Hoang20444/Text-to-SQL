package com.fsa.textsql.repository;

import java.util.List;
import java.util.Map;

public interface ChatHistoryRepository {

    void saveLog(String sessionId, String question, String sql, String status, String errorMessage, int rowCount, long executionTimeMs);

    List<Map<String, Object>> findRecentHistory(String sessionId, int limit);
}
