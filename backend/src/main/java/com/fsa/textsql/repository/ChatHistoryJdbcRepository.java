package com.fsa.textsql.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * ASSIGNED TO: Dev 2 (Data Warehouse & Core Engine)
 */
@Repository
public class ChatHistoryJdbcRepository implements ChatHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryJdbcRepository.class);

    private final JdbcTemplate jdbcTemplate;

    // Standard constructor injection
    public ChatHistoryJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveLog(String sessionId, String question, String sql, String status, 
                        String errorMessage, int rowCount, long executionTimeMs) {
        // TODO: [Dev 2] Write INSERT INTO chat_history (...) VALUES (...)
        log.info("[TODO Dev 2] Save chat history log for question: {}", question);
        String insertSql = """
            INSERT INTO chat_history (session_id, user_question, generated_sql, execution_status, error_message, row_count, execution_time_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
        try {
            jdbcTemplate.update(insertSql, sessionId, question, sql, status, errorMessage, rowCount, executionTimeMs);
        } catch (Exception e) {
            log.warn("Could not save chat history log: {}", e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> findRecentHistory(String sessionId, int limit) {
        // TODO: [Dev 2] Write SELECT * FROM chat_history WHERE session_id = ? ORDER BY created_at DESC LIMIT ?
        String querySql = "SELECT * FROM chat_history WHERE session_id = ? ORDER BY created_at DESC LIMIT ?";
        try {
            return jdbcTemplate.queryForList(querySql, sessionId, limit);
        } catch (Exception e) {
            log.warn("Could not fetch chat history: {}", e.getMessage());
            return List.of();
        }
    }
}
