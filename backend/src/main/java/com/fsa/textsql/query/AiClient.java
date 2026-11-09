package com.fsa.textsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsa.textsql.common.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Calls POST /internal/v1/text-to-sql on the Python AI service. The request carries only the
 * question and the semantic context (table/column names), never warehouse credentials. Python error
 * codes are mapped to the public error model; raw provider bodies are not forwarded.
 */
@Component
public class AiClient {

  private static final Logger log = LoggerFactory.getLogger(AiClient.class);
  private static final Set<String> REJECTION_CODES =
      Set.of("INSUFFICIENT_CONTEXT", "UNSUPPORTED_QUESTION", "LOW_CONFIDENCE");

  private final AiProperties properties;
  private final ObjectMapper json;
  private final HttpClient http;

  public AiClient(AiProperties properties, ObjectMapper json) {
    this.properties = properties;
    this.json = json;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  public AiCandidate generate(Map<String, Object> request, String correlationId, String mode) {
    String token = properties.token();
    if (token == null || token.length() < 32) {
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          "LLM_ERROR",
          "Dịch vụ AI chưa được cấu hình.",
          false,
          Map.of("reasonCode", "AI_SERVICE_NOT_CONFIGURED"));
    }
    HttpResponse<String> response;
    try {
      HttpRequest httpRequest =
          HttpRequest.newBuilder(
                  URI.create(properties.url().replaceAll("/+$", "") + "/internal/v1/text-to-sql"))
              .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + token)
              .header("X-Correlation-ID", correlationId)
              .header("X-Execution-Mode", mode)
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))
              .build();
      response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    } catch (HttpTimeoutException e) {
      throw new ApiException(
          HttpStatus.GATEWAY_TIMEOUT,
          "QUERY_TIMEOUT",
          "Dịch vụ AI phản hồi quá thời gian cho phép.",
          true,
          Map.of("reasonCode", "PROVIDER_TIMEOUT"));
    } catch (IOException e) {
      log.warn("AI service unreachable type={}", e.getClass().getSimpleName());
      throw llmError("PROVIDER_UNAVAILABLE", true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw llmError("PROVIDER_UNAVAILABLE", true);
    }

    if (response.statusCode() == 200) {
      try {
        AiCandidate candidate = json.readValue(response.body(), AiCandidate.class);
        if (!candidate.isComplete()) {
          throw llmError("INVALID_MODEL_RESPONSE", false);
        }
        return candidate;
      } catch (IOException e) {
        throw llmError("INVALID_MODEL_RESPONSE", false);
      }
    }
    throw mapError(response);
  }

  private ApiException mapError(HttpResponse<String> response) {
    String code = "UNKNOWN";
    try {
      JsonNode node = json.readTree(response.body()).path("error").path("code");
      if (node.isTextual()) {
        code = node.asText();
      }
    } catch (IOException ignored) {
      // Non-JSON error body: keep UNKNOWN, never forward the raw body.
    }
    int status = response.statusCode();
    if (status == 422 && REJECTION_CODES.contains(code)) {
      String message =
          switch (code) {
            case "UNSUPPORTED_QUESTION" -> "Câu hỏi chưa được hỗ trợ cho tập dữ liệu này.";
            case "LOW_CONFIDENCE" -> "Câu hỏi chưa đủ rõ để sinh SQL an toàn.";
            default -> "Không đủ ngữ cảnh dữ liệu để trả lời câu hỏi.";
          };
      return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, false, Map.of());
    }
    if (status == 422 && code.equals("SQL_DIALECT_ERROR")) {
      return new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "SQL_DIALECT_ERROR",
          "SQL sinh ra không đúng phương ngữ của kho dữ liệu.",
          false,
          Map.of());
    }
    if (status == 504) {
      return new ApiException(
          HttpStatus.GATEWAY_TIMEOUT,
          "QUERY_TIMEOUT",
          "Dịch vụ AI phản hồi quá thời gian cho phép.",
          true,
          Map.of("reasonCode", "PROVIDER_TIMEOUT"));
    }
    if (status == 401 || status == 403) {
      // Java and Python disagree on the service token: a deployment problem, not the user's.
      log.error("AI service rejected the service token status={}", status);
      return llmError("AI_SERVICE_AUTH", false);
    }
    log.warn("AI service error status={} code={}", status, code);
    return llmError(code, status == 503);
  }

  private static ApiException llmError(String reasonCode, boolean retryable) {
    return new ApiException(
        HttpStatus.BAD_GATEWAY,
        "LLM_ERROR",
        "Dịch vụ AI không sinh được câu lệnh SQL.",
        retryable,
        Map.of("reasonCode", reasonCode));
  }
}
