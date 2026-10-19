import { QueryResponse, FewShotItem, DataCatalogItem } from '../types';

export const MOCK_QUERY_RESPONSE: QueryResponse = {
  question: 'Tổng doanh thu theo từng quốc gia của khách hàng?',
  generatedSql: 'SELECT c.country, ROUND(SUM(i.total)::numeric, 2) AS total_revenue\nFROM customer c\nJOIN invoice i ON c.customer_id = i.customer_id\nGROUP BY c.country\nORDER BY total_revenue DESC\nLIMIT 6;',
  columns: ['country', 'total_revenue'],
  data: [
    { country: 'USA', total_revenue: 523.06 },
    { country: 'Canada', total_revenue: 303.96 },
    { country: 'France', total_revenue: 195.10 },
    { country: 'Brazil', total_revenue: 190.10 },
    { country: 'Germany', total_revenue: 156.48 },
    { country: 'United Kingdom', total_revenue: 112.86 }
  ],
  suggestedChart: 'BAR',
  executionTimeMs: 124,
  rowCount: 6,
  status: 'SUCCESS'
};

export const MOCK_FEW_SHOTS: FewShotItem[] = [
  {
    id: 1,
    category: 'Doanh thu & Bán hàng',
    difficulty: 'EASY',
    question: 'Tổng doanh thu theo từng quốc gia?',
    sqlQuery: 'SELECT c.country, SUM(i.total) FROM customer c JOIN invoice i...',
    explanation: 'Doanh thu nhóm theo quốc gia khách hàng'
  },
  {
    id: 2,
    category: 'Sản phẩm',
    difficulty: 'EASY',
    question: 'Top 10 bài hát có thời lượng dài nhất?',
    sqlQuery: 'SELECT name, milliseconds FROM track ORDER BY milliseconds DESC LIMIT 10;',
    explanation: 'Sắp xếp thời lượng giảm dần'
  },
  {
    id: 3,
    category: 'Khách hàng',
    difficulty: 'MEDIUM',
    question: 'Top 5 khách hàng chi tiêu nhiều nhất?',
    sqlQuery: 'SELECT c.first_name, SUM(i.total) FROM customer c JOIN invoice i...',
    explanation: 'Khách hàng VIP có tổng chi tiêu cao nhất'
  },
  {
    id: 4,
    category: 'Nghệ sĩ',
    difficulty: 'EASY',
    question: 'Có bao nhiêu nghệ sĩ trong hệ thống?',
    sqlQuery: 'SELECT COUNT(*) FROM artist;',
    explanation: 'Đếm tổng số nghệ sĩ'
  }
];

export const MOCK_DATASET_INFO = {
  name: 'Chinook Music Store Analytics',
  status: 'Active',
  tablesCount: 11,
  metricsCount: 18,
  dimensionsCount: 12,
  version: '1.4.5',
  warehouse: 'PostgreSQL 16 (Local)',
  updatedAt: '19/10/2026'
};
