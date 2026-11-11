# Backend Service (Java 21 + Spring Boot)

Thư mục dành cho thành viên Backend triển khai theo Sprint Backlog (`docs/Project_Agile_Backlog_8_Sprints.xlsx`).

## Đã có (Sprint 1)

Code backend được port từ repo toannq (package `dev.nla`) và cắt đúng phạm vi từng task Sprint 1.

| Task | Nội dung |
|---|---|
| T-1.1.1 | Spring Boot 3.5 + Flyway, Metadata DB lưu document jsonb (`warehouse`, `dataset`, `query_history`, `resource_access`, `audit_event`, `platform_settings`), adapter 3 engine (kết nối + đọc metadata), tự đăng ký 3 kho Chinook local, `GET /api/v1/warehouses[/{id}]`, `GET /api/v1/datasets[/{id}]` có phân trang/tìm kiếm/sắp xếp, error envelope + `X-Correlation-ID` |
| T-1.2.1 | Keycloak realm `nla` với 3 tài khoản `admin`/`analyst`/`viewer`, Spring Security kiểm tra chữ ký JWT (issuer, audience, subject), `GET /api/v1/auth/session`, chặn quyền theo từng kho (bảng `resource_access`): không token → 401, thiếu vai trò hoặc sai kho → 403. Không có chế độ bỏ qua đăng nhập |
| T-1.3.2 | `POST /api/v1/query`: kiểm tra quyền kho → dựng ngữ cảnh từ bảng thật được phép + semantic model → gọi Python AI lấy SQL ứng viên → kiểm tra chỉ một câu SELECT → kiểm tra lại quyền → chạy chỉ đọc trên PostgreSQL/MySQL/SQL Server với giới hạn dòng/byte/thời gian → lưu `query_history`. Kèm `GET /api/v1/query/{id}` và `GET /api/v1/query/history` |

## Sprint 2

| Task | Nội dung |
|---|---|
| T-2.1.1 | Quản lý kho: `POST /api/v1/warehouses` (ADMIN), `GET /api/v1/warehouses/{id}`, `POST /api/v1/warehouses/{id}/test` (độ trễ hoặc lý do lỗi không lộ mật khẩu). Quét cấu trúc chạy nền: `POST /api/v1/warehouses/{id}/discover` → 202, `GET /api/v1/warehouses/{id}/discoveries/{runId}` (schema, bảng, cột, kiểu, nullable, PK, FK, `schemaVersion`). `POST /api/v1/datasets` (ADMIN). Data Catalog: `GET /api/v1/datasets/{id}/catalog`, `/tables`, `/tables/{tableId}`, `/relationships` |

Luồng cho một kho mới (ADMIN): đăng ký → `test` → `discover` (đợi `SUCCESS`) → tạo dataset → xem Catalog. Kho Chinook nạp sẵn khi chạy local cũng cần bấm `discover` một lần thì Catalog mới có dữ liệu.

## Cấu hình

Backend đọc cấu hình từ biến môi trường hoặc các file trong `.local/` (đã bị Git ignore):

- `.local/docker.env`: do `data/scripts/docker_chinook.py` sinh ra.
- `.local/oidc/backend.env`: do `infra/local/oidc.py init` sinh ra.

| Biến | Ý nghĩa |
|---|---|
| `METADATA_JDBC_URL`, `METADATA_USERNAME`, `METADATA_PASSWORD` | Metadata DB `nla_metadata` (tài khoản `nla_app`) |
| `CHINOOK_DOCKER=true` | Tự đăng ký 3 kho Chinook local (PostgreSQL, MySQL, SQL Server) và quyền mẫu khi khởi động |
| `NLA_MYSQL_PORT` | Cổng MySQL nếu khác `53306` |
| `NLA_DATA_ROOT` | Đường dẫn tới thư mục `data/` của repo. Bỏ trống thì tự tìm `./data` hoặc `../data`, nên chạy từ gốc repo hay từ `backend/` đều được |
| `OIDC_ISSUER_URI` | Realm Keycloak cấp token (mặc định `http://127.0.0.1:8180/realms/nla`) |
| `OIDC_AUDIENCE` | Audience bắt buộc trong token (mặc định `nla-api`) |
| `OIDC_JWK_SET_URI` | Chỉ cần khi backend gọi Keycloak qua địa chỉ khác issuer (ví dụ trong Docker) |
| `NLA_LOCAL_ANALYST_SUBJECT`, `NLA_LOCAL_VIEWER_SUBJECT` | ID tài khoản mẫu để cấp quyền kho khi chạy local |
| `PYTHON_AI_URL` | Địa chỉ Python AI service, ví dụ `http://127.0.0.1:8000` (`docker_chinook.py` ghi sẵn vào `.local/docker.env`) |
| `WAREHOUSE_ALLOWED_HOSTS` | Danh sách host kho được phép kết nối (mặc định `127.0.0.1,localhost`) |
| `PYTHON_AI_TOKEN` | Service token dùng chung giữa Java và Python (≥ 32 ký tự). Phải đặt **cùng giá trị** cho cả hai tiến trình; không có trong Git |
| `NLA_MYSQL_RSA_PUBLIC_KEY` | File khóa RSA của MySQL do `docker_chinook.py` lưu (dùng để đăng nhập MySQL 8 không cần TLS) |

Mật khẩu kho dữ liệu không lưu trong DB: bảng `warehouse` chỉ giữ `credential_reference` dạng `env:NLA_CHINOOK_PG_PASSWORD`, và API không bao giờ trả trường này. Backend không lưu mật khẩu người dùng: chỉ kiểm tra token do Keycloak ký.

## Phân quyền

| Vai trò | Kho được dùng (local) |
|---|---|
| `ADMIN` | Tất cả kho |
| `ANALYST` | Kho được cấp trong `resource_access` (local: cả 3 kho) |
| `VIEWER` | Kho được cấp trong `resource_access` (local: chỉ `wh-chinook-pg`) |

Mọi `/api/v1/*` cần header `Authorization: Bearer <token>`. Chi tiết trong `docs/contracts/public-api.md` (mục Auth).

## Chạy

> Nếu máy từng chạy repo khác dùng cùng tên Docker `nla-chinook`/`nla-auth`, đặt tên riêng trước khi chạy script, ví dụ `COMPOSE_PROJECT_NAME=nla-tts` cho CSDL và `nla-tts-auth` cho Keycloak.

```bash
# 1. Dựng 3 CSDL và nạp Chinook (một lần)
python data/scripts/docker_chinook.py init
python data/scripts/docker_chinook.py up
python data/scripts/docker_chinook.py provision

# 2. Keycloak + 3 tài khoản mẫu (mật khẩu trong .local/oidc/accounts.json)
python infra/local/oidc.py init
python infra/local/oidc.py up

# 3. Python AI service (cổng 8000) - cùng PYTHON_AI_TOKEN với backend
export PYTHON_AI_TOKEN=<chuỗi ngẫu nhiên tối thiểu 32 ký tự>
cd ai-service && pip install -r requirements.txt
uvicorn app:app --host 127.0.0.1 --port 8000

# 4. Chạy backend (cổng 8080), terminal khác, cùng PYTHON_AI_TOKEN
cd backend
mvn spring-boot:run
```

> Đổi từ bản `com.fsa.textsql` cũ sang bản port này thì Flyway history khác nhau: xóa và tạo lại database `nla_metadata` trước khi chạy (dữ liệu trong đó chỉ là metadata local, tự đăng ký lại khi khởi động).

Hỏi một câu mẫu (chế độ `DETERMINISTIC`: Python chỉ trả SQL cho câu hỏi khớp một câu mẫu trong `data/chinook/semantic-*.json`):

```bash
TOKEN=$(python infra/local/oidc.py token analyst)
curl -X POST http://localhost:8080/api/v1/query \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"warehouseId":"wh-chinook-mysql","datasetId":"ds-chinook-mysql","question":"How many tracks are in the catalog?","mode":"DETERMINISTIC"}'
```

`VIEWER` chỉ được xem, không được chạy truy vấn (403). Lỗi sau khi đã tạo truy vấn (AI từ chối, SQL không an toàn, lỗi kho) vẫn trả `201` với `status` (`REJECTED`, `VALIDATION_ERROR`, `QUERY_ERROR`, `TIMEOUT`) và `error` kèm `queryId`. Kết quả tối đa 1000 dòng / 5 MB (`returnedRowCount`, `truncated`), response trả 20 dòng đầu; DECIMAL/BIGINT là chuỗi để không mất độ chính xác, thời gian là ISO-8601 UTC.

Kiểm tra nhanh (lấy token của tài khoản mẫu từ Keycloak local):

```bash
TOKEN=$(python infra/local/oidc.py token viewer)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/auth/session
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/warehouses
curl -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/datasets?warehouseId=wh-chinook-mysql"   # 403
```

Client `nla-local-cli` dùng cho lệnh `token` chỉ có trong realm local do script sinh ra, dùng để thử API trước khi có màn hình đăng nhập. Ứng dụng web dùng client `nla-web` (Authorization Code + PKCE).

## Test

```bash
cd backend
mvn test
```

Test tích hợp dùng Testcontainers (PostgreSQL 16, MySQL 8.4, SQL Server 2022) nên cần Docker đang chạy. Test phân quyền tự ký token RS256 và kiểm tra bằng đúng bộ validator của production (chữ ký, hạn, issuer, audience). Python AI được thay bằng HTTP server giả chỉ trong test; chạy với AI service thật làm theo mục Chạy ở trên.
