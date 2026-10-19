-- 02-app-system.sql
-- Application System Tables (Audit log, Chat History, Few-Shot Storage)

CREATE TABLE IF NOT EXISTS chat_history (
    id SERIAL PRIMARY KEY,
    session_id VARCHAR(100) DEFAULT 'default-session',
    user_question TEXT NOT NULL,
    generated_sql TEXT,
    execution_status VARCHAR(30) NOT NULL, -- 'SUCCESS', 'SYNTAX_ERROR', 'GUARDRAIL_BLOCKED'
    error_message TEXT,
    row_count INT DEFAULT 0,
    execution_time_ms BIGINT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS few_shot_example (
    id SERIAL PRIMARY KEY,
    category VARCHAR(50) NOT NULL, -- 'Revenue', 'Customer', 'Product', 'Artist'
    difficulty VARCHAR(20) DEFAULT 'EASY', -- 'EASY', 'MEDIUM', 'HARD'
    question TEXT NOT NULL,
    sql_query TEXT NOT NULL,
    explanation TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Seed Initial Few-shot Examples for Chinook Music Store
INSERT INTO few_shot_example (category, difficulty, question, sql_query, explanation) VALUES
('Product', 'EASY', 'Danh sách 10 bài hát có thời lượng dài nhất', 
'SELECT name, milliseconds / 1000 AS duration_seconds FROM track ORDER BY milliseconds DESC LIMIT 10;', 
'Sắp xếp bảng track theo milliseconds giảm dần và lấy 10 dòng đầu'),

('Artist', 'EASY', 'Có bao nhiêu nghệ sĩ trong hệ thống?', 
'SELECT COUNT(*) AS total_artists FROM artist;', 
'Đếm tổng số bản ghi trong bảng artist'),

('Revenue', 'MEDIUM', 'Tổng doanh thu theo từng quốc gia của khách hàng', 
'SELECT c.country, ROUND(SUM(i.total)::numeric, 2) AS total_revenue FROM customer c JOIN invoice i ON c.customer_id = i.customer_id GROUP BY c.country ORDER BY total_revenue DESC;', 
'JOIN giữa customer và invoice, nhóm theo country và tính tổng total'),

('Customer', 'MEDIUM', 'Top 5 khách hàng chi tiêu nhiều nhất', 
'SELECT c.customer_id, c.first_name || '' '' || c.last_name AS customer_name, ROUND(SUM(i.total)::numeric, 2) AS total_spent FROM customer c JOIN invoice i ON c.customer_id = i.customer_id GROUP BY c.customer_id, customer_name ORDER BY total_spent DESC LIMIT 5;', 
'Tính tổng chi tiêu của từng khách hàng và lấy 5 người cao nhất'),

('Revenue', 'HARD', 'Doanh thu theo từng thể loại nhạc (Genre)', 
'SELECT g.name AS genre_name, ROUND(SUM(il.unit_price * il.quantity)::numeric, 2) AS revenue FROM invoice_line il JOIN track t ON il.track_id = t.track_id JOIN genre g ON t.genre_id = g.genre_id GROUP BY g.name ORDER BY revenue DESC;', 
'JOIN giữa invoice_line, track và genre để tính doanh thu bán nhạc theo từng thể loại');
