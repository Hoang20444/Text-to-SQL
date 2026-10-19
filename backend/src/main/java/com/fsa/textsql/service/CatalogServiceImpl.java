package com.fsa.textsql.service;

import com.fsa.textsql.dto.DataCatalogDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ASSIGNED TO: Dev 2 (Data Warehouse & Core Engine)
 * Reads information_schema to provide full catalog for UI & AI context
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogServiceImpl implements CatalogService {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<DataCatalogDto> getDatasetCatalog() {
        // Query list of user tables in Chinook database
        String sql = """
            SELECT table_name 
            FROM information_schema.tables 
            WHERE table_schema = 'public' 
              AND table_type = 'BASE TABLE'
              AND table_name NOT IN ('chat_history', 'few_shot_example')
            ORDER BY table_name;
        """;

        List<DataCatalogDto> result = new ArrayList<>();
        try {
            List<String> tableNames = jdbcTemplate.queryForList(sql, String.class);
            for (String tableName : tableNames) {
                List<DataCatalogDto.ColumnMetaDto> columns = getColumnsForTable(tableName);
                result.add(DataCatalogDto.builder()
                        .tableName(tableName)
                        .description("Bảng lưu trữ thông tin " + tableName)
                        .columnCount(columns.size())
                        .columns(columns)
                        .build());
            }
        } catch (Exception e) {
            log.warn("Could not query information_schema: {}", e.getMessage());
        }
        return result;
    }

    private List<DataCatalogDto.ColumnMetaDto> getColumnsForTable(String tableName) {
        String sql = """
            SELECT column_name, data_type 
            FROM information_schema.columns 
            WHERE table_schema = 'public' AND table_name = ?
            ORDER BY ordinal_position;
        """;
        List<DataCatalogDto.ColumnMetaDto> columns = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, tableName);
            for (Map<String, Object> row : rows) {
                columns.add(DataCatalogDto.ColumnMetaDto.builder()
                        .name((String) row.get("column_name"))
                        .type((String) row.get("data_type"))
                        .description("Cột " + row.get("column_name"))
                        .isPrimaryKey(row.get("column_name").toString().endsWith("_id"))
                        .build());
            }
        } catch (Exception e) {
            log.warn("Could not query columns for table {}: {}", tableName, e.getMessage());
        }
        return columns;
    }
}
