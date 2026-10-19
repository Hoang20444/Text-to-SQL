package com.fsa.textsql.dto;

public class FewShotExampleDto {

    private Long id;
    private String category;
    private String difficulty;
    private String question;
    private String sqlQuery;
    private String explanation;

    public FewShotExampleDto() {
    }

    public FewShotExampleDto(Long id, String category, String difficulty, String question, String sqlQuery, String explanation) {
        this.id = id;
        this.category = category;
        this.difficulty = difficulty;
        this.question = question;
        this.sqlQuery = sqlQuery;
        this.explanation = explanation;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getSqlQuery() { return sqlQuery; }
    public void setSqlQuery(String sqlQuery) { this.sqlQuery = sqlQuery; }

    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
}
