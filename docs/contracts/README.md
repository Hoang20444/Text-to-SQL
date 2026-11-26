# Shared Contract Baseline (Sprint 1 – Sprint 4)

Tài liệu quy ước giao tiếp chung giữa 3 thành viên (`Frontend`, `Backend`, `AI & Data`):

1. [Domain](domain.md): Mô hình dữ liệu chung (`Warehouse`, `Dataset`, `Query`, `Result`, `Chart`, `Pagination`) và bộ mã enum `POSTGRESQL`, `MYSQL`, `SQL_SERVER`.
2. [Semantic Model](semantic-model.md): Cấu trúc ngữ cảnh (`semanticContext`) Backend gửi sang dịch vụ Python AI.
3. [Java ↔ Python](java-python.md): Hợp đồng giao tiếp nội bộ `/internal/v1/text-to-sql`, xác thực `PYTHON_AI_TOKEN`, `X-Correlation-ID` và kiểm soát lỗi.
4. [Semantic Planner](semantic-planner.md): Hợp đồng lập kế hoạch ngữ nghĩa nội sinh `/internal/v1/semantic-plan` (`wren-core-py`), kiểm tra lineage bảng vật lý và nén SQL an toàn cho 3 phương ngữ.
5. [Semantic Workbench & Knowledge](semantic-workbench.md): Hợp đồng biên dịch mô hình nghiệp vụ (`/internal/v1/business-validate`, `/internal/v1/business-plan`), trợ lý nháp ngữ nghĩa (`/internal/v1/semantic-assist`), kho tri thức vector (`/internal/v1/knowledge/{action}`) và hỏi nối tiếp/biểu đồ (Sprint 3–4).
6. [Model Providers](model-providers.md): Hợp đồng cấu hình mô hình AI (`OLLAMA` nội bộ & `ONLINE` OpenAI-compatible), mã hóa khóa API và endpoint `/internal/v1/model-provider/status`.
7. [Public API](public-api.md): Hợp đồng REST API `/api/v1/*` giữa giao diện React và Java Backend.
8. [Errors](errors.md): Bảng mã lỗi HTTP và trạng thái truy vấn chuẩn hóa toàn hệ thống.
9. [Examples](examples/): Bộ file JSON mẫu dùng chung cho kiểm thử độc lập ở cả 3 tầng `FE`, `BE`, `AI`.
