# Nhật ký lỗi & vấn đề — Sprint 1 (BE)

Ghi lại mọi lỗi/vấn đề phát hiện trong quá trình làm Sprint 1 BE (09/11/2026), kèm người/phần phụ trách và trạng thái. "Phụ trách" lấy theo tác giả commit hoặc vai trò trong backlog, không nhằm quy lỗi cá nhân mà để biết ai cần xử lý.

Ký hiệu trạng thái: ✅ đã sửa · 🟡 đã có cách tạm/giới hạn đã biết · 🔴 chưa sửa, cần người phụ trách xử lý.

## 1. Repo `Hoang20444/Text-to-SQL` — phần AI/Data & hạ tầng (tác giả commit: Hoang20444)

| # | Vấn đề | Ảnh hưởng | Trạng thái |
|---|---|---|---|
| 1.1 | `data/scripts/docker_chinook.py` dòng 384: câu kiểm tra `nla_importer` trên SQL Server thiếu `SET NOCOUNT ON` + `GO`. Kết quả trả kèm "(1 rows affected)" nên script tưởng login đã tồn tại và chạy `ALTER LOGIN` → lỗi. | Trên máy mới, bước `provision` SQL Server **luôn thất bại**. | 🔴 BE đã sửa 1 dòng ở máy để chạy được, **chưa commit** (file của AI/Data). Cần AI/Data sửa trên nhánh của mình. |
| 1.2 | `infra/compose.databases.yml` (`name: nla-chinook`) và `infra/compose.auth.yml` (`name: nla-auth`) trùng tên dự án Docker với repo toannq. | Máy từng chạy repo toannq sẽ dùng lại volume cũ (mật khẩu khác, `nla_metadata` có bảng của backend khác) → provision lỗi, Flyway xung đột, Keycloak dùng realm cũ. | 🟡 Tạm thời đặt `COMPOSE_PROJECT_NAME=nla-tts` / `nla-tts-auth`. Đề xuất đổi `name:` trong 2 file compose. |
| 1.3 | `infra/compose.auth.yml` yêu cầu `infra/local/oidc.py init` và thư mục `.local/oidc/import` nhưng repo chưa có script này. | Không bật được Keycloak. | ✅ BE viết `infra/local/oidc.py` trong T-1.2.1 (commit `1c2f7eb`). |
| 1.4 | `docker_chinook.py` ghi `PYTHON_AI_TOKEN=` (rỗng) vào `.local/docker.env`; không sinh token. | AI service từ chối mọi request (cần token ≥ 32 ký tự) → luồng query không chạy cho tới khi tự đặt token cho cả Java và Python. | 🔴 Đề xuất script tự sinh `PYTHON_AI_TOKEN`. Hướng dẫn tạm có trong `backend/README.md`. |
| 1.5 | Câu mẫu "Tổng doanh số theo quốc gia thanh toán?" (3 file `data/chinook/semantic-*.json`) chỉ `ORDER BY total_sales DESC`, không có tie-break. | Các quốc gia cùng doanh số (45.62, 37.62) bị sắp khác nhau giữa PG/MySQL/SQL Server → đối chiếu AC4 (US-1.3) báo lệch dù dữ liệu giống hệt. | 🔴 Thêm `, billing_country` vào ORDER BY ở cả 3 dialect. |
| 1.6 | `CONTRIBUTING.md` dẫn tới `AGENTS.md`, `docs/implementation-rules.md`, `docs/work-log.md` (không tồn tại) và quy ước nhánh `module/<scope>`, trong khi nhóm đang dùng `feat/...`. | Hướng dẫn đóng góp không khớp thực tế (copy từ repo toannq). | 🔴 Cần nhóm thống nhất và sửa lại. |
| 1.7 | `data/chinook/README.md` ghi semantic mapping nằm ở `../scripts/semantic-*.json`, thực tế file ở `data/chinook/`. | Tài liệu chỉ sai chỗ. | 🔴 Sửa đường dẫn. |
| 1.8 | Commit `7d6449b` xóa toàn bộ backend/frontend/evaluation của skeleton cũ. | Không phải lỗi, nhưng cần thông báo cho FE/BE vì đổi hoàn toàn nền tảng. | Ghi nhận. |
| 1.9 | `docker_chinook.py` truyền SQL qua `subprocess` với `text=True` không chỉ định UTF-8; dữ liệu Chinook có ký tự Unicode. | Có thể lỗi mã hóa trên Windows. | 🟡 Phòng ngừa bằng `PYTHONUTF8=1`, **chưa xác nhận** là lỗi thật. |

## 2. Skeleton cũ của repo Hoang (`ee4a666`, đã bị xóa ở `7d6449b`)

Ghi lại để không lặp lại khi làm lại (đã được giải quyết vì code bị xóa):

| # | Vấn đề |
|---|---|
| 2.1 | Mật khẩu `postgrespassword` ghi cứng trong `docker-compose.yml` và `application.yml`. |
| 2.2 | API key Gemini đặt trên URL (`?key=...`), dễ lộ vào log. |
| 2.3 | Thiếu key thì trả câu SQL giả (mock) thay vì báo lỗi. |
| 2.4 | Guardrail chỉ kiểm tra "là SELECT", không kiểm tra bảng được phép, CTE, `SELECT INTO`. |
| 2.5 | Chạy query bằng tài khoản `postgres` (toàn quyền), không có tài khoản chỉ đọc. |
| 2.6 | Trả `e.getMessage()` cho người dùng (lộ chi tiết DB); không có test; không có auth. |
| 2.7 | Gemini gọi trực tiếp từ Java, trái kiến trúc React → Java → Python. |

## 3. Repo `toannnnq1424/text-to-sql` (`E:\Fresher\project`, tác giả: Nguyễn Quang Toàn)

| # | Vấn đề | Trạng thái |
|---|---|---|
| 3.1 | Profile `local`: mọi `GET /api/v1/**` cho phép ẩn danh và `Store.authorize()` bỏ qua kiểm tra quyền kho. | 🔴 Lệch AC "môi trường phát triển vẫn yêu cầu danh tính". |
| 3.2 | Phân quyền theo warehouse, chưa theo dataset (so với backlog bản cũ). | Ghi nhận (backlog bản (2) đã chốt theo warehouse). |
| 3.3 | Working copy có 3 file sửa chưa commit không rõ nguồn: `CatalogService.java`, `RegistryController.java`, `data/scripts/docker_chinook.py`. | 🔴 Cần chủ repo kiểm tra. BE không đụng tới. |
| 3.4 | Volume Docker `nla-chinook_*`, `nla-auth_*`, `nla-m2-cache` từ 06/11 vẫn còn trên máy. | 🟡 Đã dừng 3 container DB cũ, chưa xóa (chờ quyết định). |

## 4. Tài liệu backlog

| # | Vấn đề | Trạng thái |
|---|---|---|
| 4.1 | Có nhiều bản backlog mâu thuẫn: "Thuc_Te_V2" (SQL Import/ETL/OIDC để sau 8 tuần), bản (1), bản (2) (bắt buộc từ đầu). | ✅ Chốt dùng bản (2) theo chỉ đạo người dùng. |
| 4.2 | T-1.2.1 yêu cầu `/api/v1/auth/session` nhưng `docs/contracts` chưa có; contract có 4 vai trò (thêm OPERATOR) trong khi backlog Sprint 1 có 3. | ✅ BE thêm mục Auth + `examples/auth-session.json` vào contract (`1c2f7eb`). |
| 4.3 | US-1.3 AC1 cần API câu hỏi gợi ý theo dataset nhưng không task BE nào liệt kê. | ✅ BE thêm `GET /api/v1/datasets/{id}/examples` (`8098680`, đã có trong contract). |
| 4.4 | `query-request.json` mẫu dùng `mode: DETERMINISTIC`, contract mặc định `AUTO`; Sprint 1 chưa có LLM thật nên FE phải gửi `DETERMINISTIC`. | 🟡 Ghi trong `backend/README.md`; cần báo FE. |

## 5. Phần BE (Sprint 1, nhánh `feat/sprint1-be`)

| # | Vấn đề | Trạng thái |
|---|---|---|
| 5.1 | Đường dẫn dữ liệu mặc định `../data` → backend crash khi IDE chạy từ thư mục gốc repo (`Files.readString` trong `LocalChinookBootstrap`). | ✅ Tự dò `./data` hoặc `../data` (`70a5437`). |
| 5.2 | Đường dẫn file khóa RSA MySQL (Windows `E:\...`) ghép vào URL JDBC → `SQLState S1009`, mọi query MySQL lỗi kết nối. | ✅ Truyền qua driver property (trước commit `8098680`). |
| 5.3 | **Chưa cấu hình CORS**: FE chạy `localhost:5173` gọi thẳng `localhost:8080` sẽ bị trình duyệt chặn. | 🔴 Cần thống nhất với FE (dùng Vite proxy hoặc BE thêm CORS cho origin dev). |
| 5.4 | Từ T-1.2.1 mọi `/api/v1/*` cần token → FE đang gọi API không token sẽ nhận 401. | 🟡 Đã ghi trong contract; cần báo FE dùng realm `nla`, client `nla-web`. |
| 5.5 | Kiểm tra SQL ở Sprint 1 còn tối thiểu (1 câu SELECT, chặn từ khóa ghi); chưa kiểm tra bảng ngoài allowlist. | 🟡 Giới hạn đã biết; AST guard đầy đủ là T-2.2.1 (Sprint 2). Lớp bảo vệ chính: tài khoản DB chỉ đọc. |
| 5.6 | `rows` chỉ trả 100 dòng đầu (contract `pageSize ≤ 100`); chưa có `GET /query/{id}/result` để xem trang sau. | 🟡 `returnedRowCount`/`truncated` cho biết còn dữ liệu; endpoint phân trang làm ở task sau. |
| 5.7 | Trạng thái `ACTIVE` của 3 kho được gán khi đăng ký, chưa kiểm tra kết nối thật. | 🟡 Test Connection thuộc Sprint 2. |
| 5.8 | Chưa có `GET /warehouses/{id}`, `GET /datasets/{id}` (có trong contract, không thuộc task Sprint 1). | 🟡 Làm khi task cần. |
| 5.9 | Client Keycloak `nla-local-cli` (password grant) là phần thêm ngoài backlog để lấy token thử API. | 🟡 Chỉ có trong realm local do script sinh. |
| 5.10 | Chưa có Maven Wrapper (đã thêm rồi hoàn tác theo yêu cầu người dùng); README dùng `mvn`. | 🟡 Người dùng tự thêm. |
| 5.11 | Git cảnh báo LF/CRLF khi commit (repo chưa có `.gitattributes`). | 🟡 Không ảnh hưởng nội dung; có thể thêm `.gitattributes`. |

## 6. Sai sót trong quá trình hỗ trợ (trợ lý/ môi trường)

| # | Vấn đề |
|---|---|
| 6.1 | Kết luận nhầm "máy chưa có Python" do lệnh `python` trỏ tới lối tắt Microsoft Store; thực tế có Python 3.12 (dùng lệnh `py`). |
| 6.2 | Ban đầu phân tích Sprint 1 theo bản backlog cũ; người dùng yêu cầu bám bản (2) và đã điều chỉnh. |
| 6.3 | Tự thêm Maven Wrapper khi người dùng muốn tự làm → đã hoàn tác. |
| 6.4 | Lệnh dừng container + chạy tên Docker riêng bị hệ thống chặn tự động lần đầu; chạy lại sau khi người dùng đồng ý. |
| 6.5 | PowerShell 5.1 không đọc được body của response 401 (tưởng body rỗng); kiểm tra lại bằng `curl` thì body đúng chuẩn. Không phải lỗi backend. |
