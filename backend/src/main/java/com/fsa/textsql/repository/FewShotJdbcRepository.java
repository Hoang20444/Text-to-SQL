package com.fsa.textsql.repository;

import com.fsa.textsql.dto.FewShotExampleDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ASSIGNED TO: Dev 2 / Dev 3
 */
@Repository
public class FewShotJdbcRepository implements FewShotRepository {

    private static final Logger log = LoggerFactory.getLogger(FewShotJdbcRepository.class);

    private final JdbcTemplate jdbcTemplate;

    // Standard constructor injection
    public FewShotJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<FewShotExampleDto> findAllActive() {
        String sql = "SELECT id, category, difficulty, question, sql_query, explanation FROM few_shot_example WHERE is_active = TRUE ORDER BY id ASC";
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> new FewShotExampleDto(
                    rs.getLong("id"),
                    rs.getString("category"),
                    rs.getString("difficulty"),
                    rs.getString("question"),
                    rs.getString("sql_query"),
                    rs.getString("explanation")
            ));
        } catch (Exception e) {
            log.warn("Could not load few shot examples: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<FewShotExampleDto> findByCategory(String category) {
        String sql = "SELECT id, category, difficulty, question, sql_query, explanation FROM few_shot_example WHERE is_active = TRUE AND category = ? ORDER BY id ASC";
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> new FewShotExampleDto(
                    rs.getLong("id"),
                    rs.getString("category"),
                    rs.getString("difficulty"),
                    rs.getString("question"),
                    rs.getString("sql_query"),
                    rs.getString("explanation")
            ), category);
        } catch (Exception e) {
            log.warn("Could not load few shot examples for category {}: {}", category, e.getMessage());
            return List.of();
        }
    }
}
