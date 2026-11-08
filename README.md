# Natural Language Analytics Warehouse (Multi-Warehouse Text-to-SQL)

Nền tảng trợ lý phân tích dữ liệu đa kho (`PostgreSQL`, `MySQL`, `SQL Server`) bằng ngôn ngữ tự nhiên (Tiếng Việt / Tiếng Anh), phát triển theo lộ trình Agile Scrum 8 Sprints cho nhóm 3 thành viên (`Frontend`, `Backend`, `AI & Data`).

- **Tài liệu Kế hoạch & Product Backlog 8 Sprints**:
  - [Project_Agile_Backlog_8_Sprints.docx](docs/Project_Agile_Backlog_8_Sprints.docx)
  - [Project_Agile_Backlog_8_Sprints.xlsx](docs/Project_Agile_Backlog_8_Sprints.xlsx)
- **Đặc tả Hợp đồng API chung (`v1.0`)**: [docs/contracts/README.md](docs/contracts/README.md)

## Kiến trúc Tổng thể

```text
React (frontend/) → Java Spring Boot (backend/) → Python AI Service (ai-service/)
                              ↓
             PostgreSQL / MySQL / SQL Server (Read-Only nla_reader)
```

- **Frontend (`frontend/`)**: Ứng dụng giao diện React + TypeScript + Vite (chỉ giao tiếp với Java Backend).
- **Backend (`backend/`)**: Trung tâm xác thực OIDC (`Keycloak`), quản lý Metadata DB, kiểm duyệt SQL và thực thi chỉ đọc.
- **AI Service (`ai-service/`)**: Dịch vụ nội bộ xác thực bằng `PYTHON_AI_TOKEN`, không giữ mật khẩu CSDL, sinh câu lệnh SQL ứng viên theo đúng phương ngữ (`POSTGRESQL`, `MYSQL`, `SQL_SERVER`).
- **Hạ tầng & Dữ liệu mẫu (`infra/`, `data/`)**: Docker Compose cho 3 hệ CSDL + Keycloak OIDC, nạp dữ liệu mẫu Chinook có kiểm tra toàn vẹn SHA-256 và xác minh quyền chỉ đọc `nla_reader`.

## Hướng dẫn Khởi chạy Nhanh (Sprint 1 — Hạ tầng 3 CSDL & AI Service)

### 1. Khởi tạo và nạp dữ liệu mẫu Chinook chỉ đọc trên 3 hệ CSDL

```bash
python data/scripts/docker_chinook.py init
python data/scripts/docker_chinook.py up
python data/scripts/docker_chinook.py status
python data/scripts/docker_chinook.py provision
```

Các cổng CSDL chỉ mở trên loopback cục bộ:
- PostgreSQL: `127.0.0.1:55432`
- MySQL: `127.0.0.1:53306`
- SQL Server: `127.0.0.1:51433`

### 2. Chạy và kiểm thử dịch vụ Python AI (`ai-service/`)

```bash
python -m venv ai-service/.venv
ai-service/.venv/Scripts/pip install -r ai-service/requirements-dev.txt
pytest ai-service/test_app.py
python data/scripts/verify_sprint1_ai.py
```
