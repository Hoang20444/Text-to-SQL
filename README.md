# Natural Language Analytics Warehouse (Multi-Warehouse Text-to-SQL)

Nền tảng trợ lý phân tích dữ liệu đa kho (`PostgreSQL`, `MySQL`, `SQL Server`) bằng ngôn ngữ tự nhiên (Tiếng Việt / Tiếng Anh), phát triển theo lộ trình Agile Scrum 8 Sprints cho nhóm 3 thành viên (`Frontend`, `Backend`, `AI & Data`).

- **Tài liệu Kế hoạch & Product Backlog 8 Sprints**:
  - [Project_Agile_Backlog_8_Sprints.docx](docs/Project_Agile_Backlog_8_Sprints.docx)
  - [Project_Agile_Backlog_8_Sprints.xlsx](docs/Project_Agile_Backlog_8_Sprints.xlsx)
- **Đặc tả Hợp đồng API chung (`v1.0` – Sprint 2)**: [docs/contracts/README.md](docs/contracts/README.md)

## Kiến trúc Tổng thể

```text
React (frontend/) → Java Spring Boot (backend/) → Python AI Service (ai-service/)
                               ↓                               ↓
              PostgreSQL / MySQL / SQL Server      Ollama Local / OpenAI-Compatible API
```

- **Frontend (`frontend/`)**: Ứng dụng giao diện React + TypeScript + Vite (chỉ giao tiếp với Java Backend).
- **Backend (`backend/`)**: Trung tâm xác thực OIDC (`Keycloak`), quản lý Metadata DB, mã hóa cấu hình Model Provider, kiểm duyệt SQL và thực thi chỉ đọc.
- **AI Service (`ai-service/`)**:
  - **Sprint 1**: Xác thực nội bộ `PYTHON_AI_TOKEN`, schema nghiêm ngặt không nhận mật khẩu CSDL, sinh SQL mẫu chuẩn (`DETERMINISTIC`) trên 3 hệ CSDL.
  - **Sprint 2**: Thu gọn ngữ cảnh lược đồ vật lý & giữ đường nối `JOIN` ngắn nhất (`schema_relevance.py`, `SemanticContextSelector`), hậu kiểm câu lệnh SQL ứng viên cho 3 phương ngữ, kết nối bất đồng bộ với **Ollama cục bộ (`qwen3:4b`)** & **OpenAI-compatible API** (`model_provider.py`, `OpenAICompatibleModelClient`).
- **Hạ tầng & Dữ liệu mẫu (`infra/`, `data/`)**: Docker Compose cho 3 hệ CSDL + Keycloak OIDC, nạp dữ liệu mẫu Chinook chỉ đọc `nla_reader` và tiện ích chuyển đổi hồ sơ `Ollama` / `Online` (`infra/local/ollama.py`).

## Hướng dẫn Khởi chạy & Kiểm thử Nhanh (Sprint 1 & Sprint 2 — AI & Data)

### 1. Khởi tạo và nạp dữ liệu mẫu Chinook chỉ đọc trên 3 hệ CSDL

```bash
python data/scripts/docker_chinook.py init
python data/scripts/docker_chinook.py up
python data/scripts/docker_chinook.py status
python data/scripts/docker_chinook.py provision
```

### 2. Chạy toàn bộ bộ kiểm thử tự động Sprint 1 + Sprint 2

```bash
python -m venv ai-service/.venv
ai-service/.venv/Scripts/pip install -r ai-service/requirements-dev.txt
ai-service/.venv/Scripts/pytest ai-service infra/local/test_llm_profile.py -v
ai-service/.venv/Scripts/python data/scripts/verify_sprint1_ai.py
ai-service/.venv/Scripts/python data/scripts/verify_sprint2_ai.py
```

### 3. Chạy dịch vụ AI kết nối với Ollama cục bộ (`qwen3:4b`)

```powershell
# Kích hoạt cấu hình Ollama cục bộ (.local/compose-app/ollama.env)
python infra/local/ollama.py activate --model qwen3:4b

# Chạy trực tiếp ai-service kết nối tới Ollama (http://127.0.0.1:11434/v1)
$env:PYTHON_AI_TOKEN = "sprint2-local-ai-service-secret-token-32chars"
$env:LLM_PROVIDER_KIND = "OLLAMA"
$env:LLM_BASE_URL = "http://127.0.0.1:11434/v1"
$env:LLM_MODEL = "qwen3:4b"
$env:LLM_TIMEOUT_MS = "55000"
ai-service/.venv/Scripts/python -m uvicorn app:app --app-dir ai-service --host 127.0.0.1 --port 8000
```
