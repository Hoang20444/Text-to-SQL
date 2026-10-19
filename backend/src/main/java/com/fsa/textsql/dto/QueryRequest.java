package com.fsa.textsql.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryRequest {

    @NotBlank(message = "Câu hỏi không được để trống")
    private String question;

    @Builder.Default
    private String sessionId = "default-session";

    @Builder.Default
    private String datasetId = "chinook";
}
