package com.fsa.textsql.dto;

import jakarta.validation.constraints.NotBlank;

public class QueryRequest {

    @NotBlank(message = "Câu hỏi không được để trống")
    private String question;
    private String sessionId = "default-session";
    private String datasetId = "chinook";

    public QueryRequest() {
    }

    public QueryRequest(String question, String sessionId, String datasetId) {
        this.question = question;
        this.sessionId = sessionId != null ? sessionId : "default-session";
        this.datasetId = datasetId != null ? datasetId : "chinook";
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(String datasetId) {
        this.datasetId = datasetId;
    }
}
