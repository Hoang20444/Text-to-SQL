package com.fsa.textsql.service;

import com.fsa.textsql.dto.DataCatalogDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ASSIGNED TO: Dev 2 (Data Warehouse & Core Engine)
 * Reads information_schema to provide full catalog for UI & AI context
 */
@Service
public class CatalogServiceImpl implements CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogServiceImpl.class);

    private final JdbcTemplate jdbcTemplate;

    // Standard constructor injection
    public CatalogServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

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
                result.add(new DataCatalogDto(
                        tableName,
                        "Bảng lưu trữ thông tin " + tableName,
                        columns.size(),
                        columns
                ));
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
                String colName = (String) row.get("column_name");
                String dataType = (String) row.get("data_type");
                columns.add(new DataCatalogDto.ColumnMetaDto(
                        colName,
                        dataType,
                        "Cột " + colName,
                        colName.endsWith("_id"),
                        false
                ));
            }
        } catch (Exception e) {
            log.warn("Could not query columns for table {}: {}", tableName, e.getMessage());
        }
        return columns;
    }
}
