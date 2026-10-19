package com.fsa.textsql.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FewShotExampleDto {

    private Long id;
    private String category;
    private String difficulty;
    private String question;
    private String sqlQuery;
    private String explanation;
}
