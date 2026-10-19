export interface QueryRequest {
  question: string;
  sessionId?: string;
  datasetId?: string;
}

export interface QueryResponse {
  question: string;
  generatedSql: string;
  columns?: string[];
  data?: Record<string, any>[];
  suggestedChart?: 'BAR' | 'LINE' | 'PIE' | 'TABLE' | 'METRIC';
  executionTimeMs: number;
  rowCount: number;
  status: 'SUCCESS' | 'GUARDRAIL_BLOCKED' | 'ERROR' | 'AI_ERROR';
  errorMessage?: string;
}

export interface ColumnMeta {
  name: string;
  type: string;
  description: string;
  isPrimaryKey?: boolean;
}

export interface DataCatalogItem {
  tableName: string;
  description: string;
  columnCount: number;
  columns: ColumnMeta[];
}

export interface FewShotItem {
  id: number;
  category: string;
  difficulty: 'EASY' | 'MEDIUM' | 'HARD';
  question: string;
  sqlQuery: string;
  explanation: string;
}
