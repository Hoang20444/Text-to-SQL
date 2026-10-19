package com.fsa.textsql.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsa.textsql.dto.FewShotExampleDto;
import com.fsa.textsql.repository.FewShotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * ASSIGNED TO: Dev 3 (AI Pipeline, Prompting & Benchmark)
 * Responsibilities:
 * 1. Build System Prompt with Chinook Database Schema definition
 * 2. Retrieve dynamic few-shot examples from FewShotRepository
 * 3. Call Google Gemini API (gemini-2.5-flash)
 * 4. Parse JSON response and extract clean SQL string
 */
@Service
public class GeminiAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiService.class);

    private final FewShotRepository fewShotRepository;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-2.5-flash}")
    private String modelName;

    private static final String CHINOOK_SCHEMA_DOCS = """
        Database Dialect: PostgreSQL
        Tables in Chinook Music Store:
        - artist(artist_id, name)
        - album(album_id, title, artist_id)
        - track(track_id, name, album_id, media_type_id, genre_id, composer, milliseconds, bytes, unit_price)
        - genre(genre_id, name)
        - customer(customer_id, first_name, last_name, company, address, city, state, country, postal_code, phone, email, support_rep_id)
        - employee(employee_id, last_name, first_name, title, reports_to, birth_date, hire_date, address, city, state, country, postal_code, phone, email)
        - invoice(invoice_id, customer_id, invoice_date, billing_address, billing_city, billing_state, billing_country, billing_postal_code, total)
        - invoice_line(invoice_line_id, invoice_id, track_id, unit_price, quantity)
        - playlist(playlist_id, name)
        - playlist_track(playlist_id, track_id)
        """;

    // Standard constructor injection
    public GeminiAiService(FewShotRepository fewShotRepository) {
        this.fewShotRepository = fewShotRepository;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String generateSql(String naturalLanguageQuestion) {
        log.info("[Dev 3 AI Service] Generating SQL for question: {}", naturalLanguageQuestion);

        // Fetch few-shots from repository
        List<FewShotExampleDto> fewShots = fewShotRepository.findAllActive();

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("You are an expert PostgreSQL Data Analyst. Convert natural language questions into executable PostgreSQL SQL queries for the Chinook database.\n\n");
        promptBuilder.append("=== SCHEMA DEFINITION ===\n").append(CHINOOK_SCHEMA_DOCS).append("\n\n");
        promptBuilder.append("=== GUIDELINES ===\n");
        promptBuilder.append("1. Return ONLY the raw SQL query. Do not wrap in markdown or explain.\n");
        promptBuilder.append("2. Only use SELECT statements.\n");
        promptBuilder.append("3. Match case-insensitively using ILIKE when filtering string values if appropriate.\n\n");

        promptBuilder.append("=== EXAMPLES ===\n");
        for (FewShotExampleDto ex : fewShots) {
            promptBuilder.append("Question: ").append(ex.getQuestion()).append("\n");
            promptBuilder.append("SQL: ").append(ex.getSqlQuery()).append("\n\n");
        }

        promptBuilder.append("=== USER QUESTION ===\n");
        promptBuilder.append(naturalLanguageQuestion).append("\nSQL: ");

        // If no API key configured, return fallback mock SQL for testing
        if (apiKey == null || apiKey.trim().isEmpty() || apiKey.contains("your_gemini")) {
            log.warn("Gemini API key is not configured. Returning fallback SQL.");
            return "SELECT name, milliseconds / 1000 AS duration_seconds FROM track ORDER BY milliseconds DESC LIMIT 10;";
        }

        // Call Gemini API via RestClient
        try {
            RestClient restClient = RestClient.create();
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + modelName + ":generateContent?key=" + apiKey;

            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(
                            Map.of("parts", List.of(Map.of("text", promptBuilder.toString())))
                    )
            );

            String responseJson = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(responseJson);
            String rawOutput = root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
            return cleanSql(rawOutput);
        } catch (Exception e) {
            log.error("Failed to call Gemini API: {}", e.getMessage(), e);
            throw new RuntimeException("AI Generation failed: " + e.getMessage(), e);
        }
    }

    private String cleanSql(String text) {
        if (text == null) return "";
        String s = text.trim();
        if (s.startsWith("```sql")) s = s.substring(6);
        else if (s.startsWith("```")) s = s.substring(3);
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        return s.trim();
    }
}
