package com.fsa.textsql.service;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.Limit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * ASSIGNED TO: Dev 2 (Data Warehouse & Core Engine)
 * Responsibilities:
 * 1. Parse AST with JSqlParser
 * 2. Ensure statement instanceof Select
 * 3. Enforce Limit 100 if missing or if limit > 100
 */
@Service
public class JSqlParserGuardrailService implements SqlGuardrailService {

    @Value("${guardrail.max-rows:100}")
    private long maxRows;

    @Override
    public String validateAndEnforce(String rawSql) {
        if (rawSql == null || rawSql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL query cannot be null or empty");
        }

        // Clean markdown wraps if LLM outputted ```sql ... ```
        String cleaned = rawSql.trim();
        if (cleaned.startsWith("```sql")) {
            cleaned = cleaned.substring(6);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        try {
            Statement statement = CCJSqlParserUtil.parse(cleaned);

            // TODO: [Dev 2] Verify strict SELECT only
            if (!(statement instanceof Select)) {
                throw new SecurityException("Guardrail Violation: Only SELECT statements are permitted! Got: " + statement.getClass().getSimpleName());
            }

            Select selectStatement = (Select) statement;
            if (selectStatement.getSelectBody() instanceof PlainSelect plainSelect) {
                Limit limit = plainSelect.getLimit();
                if (limit == null) {
                    Limit newLimit = new Limit();
                    newLimit.setRowCount(new net.sf.jsqlparser.expression.LongValue(maxRows));
                    plainSelect.setLimit(newLimit);
                }
            }

            return selectStatement.toString();
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            throw new IllegalArgumentException("SQL Syntax Error or Parsing Failed: " + e.getMessage(), e);
        }
    }
}
