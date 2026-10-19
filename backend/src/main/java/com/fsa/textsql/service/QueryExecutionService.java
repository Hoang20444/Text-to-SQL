package com.fsa.textsql.service;

import java.util.List;
import java.util.Map;

public interface QueryExecutionService {

    QueryResult executeQuery(String sql);

    record QueryResult(
            List<String> columns,
            List<Map<String, Object>> rows,
            String suggestedChart,
            int rowCount
    ) {}
}
