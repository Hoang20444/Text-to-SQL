package com.fsa.textsql.service;

public interface SqlGuardrailService {

    /**
     * Validates that the query is strictly a SELECT statement.
     * Prevents DROP, DELETE, UPDATE, INSERT, ALTER, TRUNCATE.
     * Ensures LIMIT is enforced (e.g. max 100 rows).
     *
     * @param rawSql generated SQL from LLM
     * @return sanitized and secured SQL
     * @throws SecurityException or IllegalArgumentException if query violates safety
     */
    String validateAndEnforce(String rawSql);
}
