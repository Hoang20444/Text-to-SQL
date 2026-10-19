package com.fsa.textsql.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataCatalogDto {

    private String tableName;
    private String description;
    private int columnCount;
    private List<ColumnMetaDto> columns;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnMetaDto {
        private String name;
        private String type;
        private String description;
        private boolean isPrimaryKey;
        private boolean isForeignKey;
    }
}
