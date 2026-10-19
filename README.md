# 🎵 Natural Language Analytics Warehouse (Text-to-SQL Chinook)

Hệ thống trợ lý phân tích dữ liệu bán hàng âm nhạc số (**Chinook Music Store**) bằng ngôn ngữ tự nhiên sử dụng **Google Gemini**, **Spring Boot (Java 21)**, **PostgreSQL** và **React (TypeScript)**.

---

## 👥 Phân Chia Công Việc Cho Nhóm 3 Fresher (Độc lập 100%)

Dự án được thiết kế theo nguyên tắc **Contract-First**: các bên đã có sẵn DTOs và Mock Data, có thể code song song ngay từ ngày đầu tiên mà không phải chờ đợi nhau.

```
text_to_sql/
├── docker-compose.yml          <-- Chạy PostgreSQL với Chinook DB & App System Tables
├── database/init/
│   ├── 01-chinook.sql          <-- 11 bảng chuẩn hóa 3NF và dữ liệu thực tế
│   └── 02-app-system.sql       <-- Bảng chat_history và few_shot_example
├── backend/                    <-- Spring Boot 3 API (Dev 2 + Dev 3)
├── frontend/                   <-- React + TypeScript + Vite UI (Dev 1)
└── evaluation/                 <-- Benchmark đo lường 40 câu hỏi eval (Dev 3)
```

---

### 👤 DEV 1: Frontend & User Interaction Lead
* **Toàn quyền phụ trách thư mục:** `frontend/`
* **Công nghệ:** React 18, TypeScript, Vite, TailwindCSS, Recharts, Lucide Icons.
* **Các file chính cần làm:**
  1. `src/components/query/QueryInputBox.tsx`: Hoàn thiện giao diện khung nhập câu hỏi, phím tắt, nút bấm.
  2. `src/components/query/SampleQueryCards.tsx`: Render các card câu hỏi mẫu theo danh mục.
  3. `src/components/result/ResultView.tsx`: Hoàn thiện hiển thị câu SQL, toggle giữa **Dạng Bảng (Table)** và **Biểu đồ (Recharts Bar/Line/Pie)**.
  4. `src/components/query/DatasetInfoCard.tsx`: Hiển thị thông số dataset.
* **Cách làm độc lập:** File `src/services/mockData.ts` đã có sẵn dữ liệu giả lập. Bạn có thể bật `npm run dev` lên và làm đẹp giao diện ngay lập tức mà không cần chờ Backend!

---

### 👤 DEV 2: Data Warehouse, Security Guardrail & Core Engine
* **Phụ trách các package trong Backend:**
  * `com.fsa.textsql.service.JSqlParserGuardrailService.java`
  * `com.fsa.textsql.service.JdbcQueryExecutionService.java`
  * `com.fsa.textsql.service.CatalogServiceImpl.java`
  * `com.fsa.textsql.repository.ChatHistoryJdbcRepository.java`
* **Công nghệ:** PostgreSQL 16, Spring JDBC (`JdbcTemplate`), `JSqlParser` (AST SQL Parser).
* **Nhiệm vụ chính:**
  1. **SQL Guardrail (`JSqlParserGuardrailService`):** Đảm bảo câu lệnh chỉ là `SELECT`. Chặn đứng mọi lệnh `DROP`, `DELETE`, `UPDATE`. Tự động chèn `LIMIT 100`.
  2. **Thực thi Dynamic SQL (`JdbcQueryExecutionService`):** Chạy query bằng `JdbcTemplate`, lấy `ResultSetMetaData` (tên cột, data type), cài đặt `queryTimeout(10)`.
  3. **Data Catalog API (`CatalogServiceImpl`):** Query `information_schema` lấy danh sách bảng và cột để trả về cho Frontend và AI.
  4. **Lưu lịch sử (`ChatHistoryJdbcRepository`):** Lưu log câu hỏi, câu SQL sinh ra, thời gian thực thi vào bảng `chat_history`.

---

### 👤 DEV 3: AI Pipeline, Prompting & Benchmark/Eval Lead
* **Phụ trách:**
  * `com.fsa.textsql.service.GeminiAiService.java`
  * `com.fsa.textsql.repository.FewShotJdbcRepository.java`
  * Toàn bộ thư mục `evaluation/` (Python scripts)
* **Công nghệ:** Google Gemini API (`gemini-2.5-flash`), RestClient / HTTP, Python 3, Pandas, Psycopg2.
* **Nhiệm vụ chính:**
  1. **Tích hợp Gemini (`GeminiAiService`):**
     * Điền `GEMINI_API_KEY` vào file `application.yml`.
     * Tinh chỉnh System Prompt: đưa Schema metadata của Chinook và các quy tắc sinh SQL chuẩn dialect PostgreSQL.
     * Tích hợp **Few-Shot RAG**: lấy các câu hỏi mẫu từ `few_shot_example` đưa vào context để tăng độ chính xác.
  2. **Bộ Benchmark 40 câu hỏi (`evaluation/run_eval.py`):**
     * Chạy script Python tự động gửi 40 câu hỏi mẫu (`benchmark_dataset.json`) vào API.
     * So sánh bảng dữ liệu kết quả giữa SQL của AI và SQL chuẩn (Execution Accuracy).
     * Xuất ra file báo cáo `eval_results_report.csv` để đưa số liệu vào slide bảo vệ đồ án!

---

## 🚀 Hướng Dẫn Khởi Chạy (Quick Start)

### 1. Bật Database PostgreSQL (Đã có sẵn data Chinook & App tables)
Yêu cầu máy đã cài Docker:
```bash
docker compose up -d
```
*(Database sẽ chạy ở port `5432` với user `postgres`, password `postgrespassword` và database `chinook_warehouse`)*.

### 2. Khởi chạy Backend (Spring Boot)
Yêu cầu Java 21 & Maven:
```bash
cd backend
mvn spring-boot:run
```
*(Backend chạy ở `http://localhost:8080`, API chính tại `POST http://localhost:8080/api/v1/query`)*.

### 3. Khởi chạy Frontend (React + Vite)
Yêu cầu Node.js 18+:
```bash
cd frontend
npm install
npm run dev
```
*(Mở trình duyệt tại `http://localhost:3000`)*.

### 4. Chạy Benchmark Đánh giá (Evaluation)
```bash
cd evaluation
pip install -r requirements.txt
python run_eval.py
```

---

## 🌿 Quy Tắc Git Flow Cho Nhóm
1. Nhánh `main`: Chỉ merge vào ngày bảo vệ đồ án.
2. Nhánh `develop`: Nhánh tích hợp chung của cả nhóm.
3. Mỗi người tạo nhánh riêng theo quy ước:
   * Dev 1: `feature/fe-query-screen`, `feature/fe-chart-view`
   * Dev 2: `feature/be-guardrail`, `feature/be-jdbc-executor`
   * Dev 3: `feature/ai-gemini-prompt`, `feature/eval-40-questions`
4. Mỗi khi xong một tính năng, tạo Pull Request (PR) vào `develop` và nhờ 1 bạn khác review code trước khi merge!
