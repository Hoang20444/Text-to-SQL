package com.fsa.textsql.repository;

import com.fsa.textsql.dto.FewShotExampleDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * ASSIGNED TO: Dev 2 / Dev 3
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class FewShotJdbcRepository implements FewShotRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<FewShotExampleDto> findAllActive() {
        String sql = "SELECT id, category, difficulty, question, sql_query, explanation FROM few_shot_example WHERE is_active = TRUE ORDER BY id ASC";
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> FewShotExampleDto.builder()
                    .id(rs.getLong("id"))
                    .category(rs.getString("category"))
                    .difficulty(rs.getString("difficulty"))
                    .question(rs.getString("question"))
                    .sqlQuery(rs.getString("sql_query"))
                    .explanation(rs.getString("explanation"))
                    .build());
        } catch (Exception e) {
            log.warn("Could not load few shot examples: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<FewShotExampleDto> findByCategory(String category) {
        String sql = "SELECT id, category, difficulty, question, sql_query, explanation FROM few_shot_example WHERE is_active = TRUE AND category = ? ORDER BY id ASC";
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> FewShotExampleDto.builder()
                    .id(rs.getLong("id"))
                    .category(rs.getString("category"))
                    .difficulty(rs.getString("difficulty"))
                    .question(rs.getString("question"))
                    .sqlQuery(rs.getString("sql_query"))
                    .explanation(rs.getString("explanation"))
                    .build(), category);
        } catch (Exception e) {
            log.warn("Could not load few shot examples for category {}: {}", category, e.getMessage());
            return List.of();
        }
    }
}
