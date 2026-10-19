package com.fsa.textsql.dto;

import java.util.List;

public class DataCatalogDto {

    private String tableName;
    private String description;
    private int columnCount;
    private List<ColumnMetaDto> columns;

    public DataCatalogDto() {
    }

    public DataCatalogDto(String tableName, String description, int columnCount, List<ColumnMetaDto> columns) {
        this.tableName = tableName;
        this.description = description;
        this.columnCount = columnCount;
        this.columns = columns;
    }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getColumnCount() { return columnCount; }
    public void setColumnCount(int columnCount) { this.columnCount = columnCount; }

    public List<ColumnMetaDto> getColumns() { return columns; }
    public void setColumns(List<ColumnMetaDto> columns) { this.columns = columns; }

    public static class ColumnMetaDto {
        private String name;
        private String type;
        private String description;
        private boolean isPrimaryKey;
        private boolean isForeignKey;

        public ColumnMetaDto() {
        }

        public ColumnMetaDto(String name, String type, String description, boolean isPrimaryKey, boolean isForeignKey) {
            this.name = name;
            this.type = type;
            this.description = description;
            this.isPrimaryKey = isPrimaryKey;
            this.isForeignKey = isForeignKey;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public boolean isPrimaryKey() { return isPrimaryKey; }
        public void setPrimaryKey(boolean primaryKey) { isPrimaryKey = primaryKey; }

        public boolean isForeignKey() { return isForeignKey; }
        public void setForeignKey(boolean foreignKey) { isForeignKey = foreignKey; }
    }
}
