package com.fsa.textsql.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ASSIGNED TO: Dev 2 (Data Warehouse & Core Engine)
 * Responsibilities:
 * 1. Execute safe SQL query on PostgreSQL Chinook DB
 * 2. Extract column names & metadata using ResultSetMetaData
 * 3. Suggest Chart type (BAR, LINE, PIE, TABLE) based on column types and count
 * 4. Enforce query timeout to prevent hanging connections
 */
@Service
public class JdbcQueryExecutionService implements QueryExecutionService {

    private static final Logger log = LoggerFactory.getLogger(JdbcQueryExecutionService.class);

    private final JdbcTemplate jdbcTemplate;

    @Value("${guardrail.timeout-seconds:10}")
    private int timeoutSeconds;

    @Value("${guardrail.max-rows:100}")
    private int maxRows;

    // Standard constructor injection
    public JdbcQueryExecutionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public QueryResult executeQuery(String sql) {
        log.info("Executing safe query: {}", sql);

        jdbcTemplate.setQueryTimeout(timeoutSeconds);
        jdbcTemplate.setMaxRows(maxRows);

        return jdbcTemplate.query(sql, rs -> {
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            List<String> columnNames = new ArrayList<>();
            List<String> columnTypes = new ArrayList<>();

            for (int i = 1; i <= columnCount; i++) {
                columnNames.add(meta.getColumnLabel(i));
                columnTypes.add(meta.getColumnTypeName(i).toUpperCase());
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(columnNames.get(i - 1), rs.getObject(i));
                }
                rows.add(row);
            }

            // Detect chart type
            String suggestedChart = detectChartType(columnTypes, rows.size());

            return new QueryResult(columnNames, rows, suggestedChart, rows.size());
        });
    }

    private String detectChartType(List<String> columnTypes, int rowCount) {
        if (rowCount == 1 && columnTypes.size() == 1) {
            return "METRIC"; // Single number card
        }
        if (columnTypes.size() == 2) {
            return "BAR"; // Category + Metric
        }
        return "TABLE";
    }
}
