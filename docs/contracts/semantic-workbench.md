# Business semantic compiler & AI semantic assistant (Sprint 3)

Tài liệu hợp đồng nội bộ cho các endpoint AI & Semantic Layer của Sprint 3 (`T-3.1.1`, `T-3.2.1`, `T-3.3.2`), bổ sung cho [Java ↔ Python AI](java-python.md) và [Java ↔ native semantic planner](semantic-planner.md). Ranh giới bảo mật giữ nguyên: React chỉ gọi Java Backend; dịch vụ Python AI chỉ giao tiếp server-to-server qua `PYTHON_AI_TOKEN` và `X-Correlation-ID`, không nhận thông tin đăng nhập kho dữ liệu và không bao giờ tự thực thi SQL. Bộ enum engine giữ nguyên `POSTGRESQL`, `MYSQL`, `SQL_SERVER`.

## 1. Business semantic validation & compilation (`T-3.1.1`)

Các endpoint nội bộ sử dụng `BusinessCompiler` (trong `ai-service/wren/nla_business.py`) dựa trên mô hình ngữ nghĩa hợp lệ (`semanticContext`):

- `POST /internal/v1/business-validate`:
  - Request: `{correlationId, warehouseId, datasetId, sqlDialect, semanticContext}` (không kèm `selection`).
  - Hành vi: kiểm tra biểu thức `metric` (phải là biểu thức tổng hợp hợp lệ, chỉ tham chiếu bảng/cột vật lý đã khai báo, chặn subquery, mutation, hàm file/network và tên cột mơ hồ), tính tương thích `dimensions`, đường nối (`relationships` / cardinality — chặn fan-out metric qua quan hệ một-nhiều/nhiều-nhiều), múi giờ (`timezone`) và SQL ví dụ (`examples`).
  - Response thành công (`200`): `{valid: true, semanticVersion, metricCount, dimensionCount, diagnostics: []}`.
  - Lỗi hợp lệ (`422`): `{code: "SEMANTIC_MODEL_INVALID", ...}` theo [Error envelope](errors.md).

- `POST /internal/v1/business-plan`:
  - Request: `{correlationId, warehouseId, datasetId, sqlDialect, semanticContext, selection}`, trong đó `selection` gồm:
    - `metrics`: danh sách tên metric (`1..10`)
    - `dimensions`: danh sách tên dimension (`0..5`, mặc định `[]`)
    - `filters`: tối đa `10` phần tử `{dimension, operator: "EQ" | "NE" | "GT" | "GTE" | "LT" | "LTE" | "CONTAINS" | "IS_NULL" | "IS_NOT_NULL", value?}`
    - `limit`: `1..1000` (mặc định `100`)
  - Hành vi: biên dịch `selection` thành SQL đọc chỉ theo đúng phương ngữ (`POSTGRESQL`, `MYSQL`, `SQL_SERVER`) bằng các biểu thức và đường nối an toàn đã cấu hình (không nhận raw SQL từ request, không gọi LLM), chạy qua `WrenEngine.plan_for_dataset` và trả về cùng cấu trúc `Candidate` 8 trường như `/internal/v1/text-to-sql` (với `executionMode` / `model` đánh dấu `semantic-compiler`).
  - Lỗi: `422 SEMANTIC_MODEL_INVALID` (hoặc `422 INVALID_REQUEST` nếu thiếu `selection`).

## 2. AI semantic draft assistant (`T-3.2.1`)

- `POST /internal/v1/semantic-assist`:
  - Request: `{correlationId, warehouseId, datasetId, sqlDialect, semanticContext, instruction, modelConfig?}` (`instruction` dài `1..2000`, giới hạn body 1 MiB, xác thực `Authorization` & `X-Correlation-ID` giống `/internal/v1/text-to-sql`).
  - Hành vi:
    - Gọi model LLM đang cấu hình (`modelConfig` hoặc biến môi trường server, qua `provider_scope`) để đề xuất bản nháp `metrics`, `dimensions` và `businessRules` bám sát metadata vật lý (`tables`, `columns`, `relationships`) hiện có của dataset — không truyền dòng dữ liệu thật, ví dụ SQL (`examples`) hay thông tin đăng nhập kho.
    - Kiểm tra nghiêm ngặt bằng Pydantic (`Proposal`) và đối chiếu chéo toàn bộ đề xuất với allowlist bảng/cột vật lý, kiểu `dataType`, cùng `BusinessCompiler`: bất kỳ đề xuất sai cột/bảng/kiểu dữ liệu, chứa biểu thức không an toàn hoặc vi phạm `BusinessCompiler` sẽ **fail-closed** (`502 INVALID_MODEL_RESPONSE`) thay vì âm thầm giữ lại hoặc kích hoạt dữ liệu sai; hợp nhất tạm với bản hiện có chỉ để kiểm tra chéo, không tự kích hoạt hay ghi vào CSDL.
  - Response (`200`): `{model, metrics, dimensions, businessRules, warnings}` (luôn kèm cảnh báo nhắc quản trị viên rà soát trước khi kích hoạt).
  - Lỗi: lỗi cấu hình/kết nối/timeout/JSON hỏng hoặc đề xuất không hợp lệ từ provider trả về mã lỗi có kiểm soát theo [Errors](errors.md) (`502 INVALID_MODEL_RESPONSE`, `504 PROVIDER_TIMEOUT`, ...), không tự bịa fallback giả.

## 3. Aggregate validation & bounded self-correction (`T-3.3.2`)

Khi `/internal/v1/text-to-sql` chạy ở chế độ LLM (`API` / `AUTO`), ứng viên SQL sau bước parse phương ngữ còn đi qua `candidate_validation.validate_aggregation`:

- Kiểm tra tính đồng nhất của biểu thức tổng hợp (`SUM`, `AVG`, `COUNT`, ...) và danh sách `GROUP BY` (chặn lỗi thiếu cột `GROUP BY`, tham chiếu nhãn/cột biểu đồ không khớp kết quả SQL, fan-out join nhân bản phép tính tổng hợp, hoặc thiếu tie-break key khi yêu cầu thứ tự/xếp hạng).
- Bảo toàn ý định phép tổng hợp vật lý mà người dùng yêu cầu rõ ràng (ví dụ không tự đổi `SUM` thành `AVG` hoặc `COUNT`).
- Khi phát hiện vi phạm `InvalidAggregation` hoặc `LOW_CONFIDENCE` trên câu lệnh tổng hợp, `OpenAICompatibleModelClient` thực hiện tối đa 1 vòng tự sửa (bounded self-correction) với phản hồi lỗi cụ thể trước khi trả kết quả hợp lệ hoặc trả mã lỗi có kiểm soát.
