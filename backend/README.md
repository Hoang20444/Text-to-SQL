# Backend Service (Java 21 + Spring Boot)

Thư mục dành cho thành viên Backend triển khai theo Sprint Backlog (`docs/Project_Agile_Backlog_8_Sprints.xlsx`).

## Đã có (Sprint 1)

| Task | Nội dung |
|---|---|
| T-1.1.1 | Spring Boot 3.5 + Flyway, Metadata DB (`warehouses`, `datasets`), `GET /api/v1/warehouses`, `GET /api/v1/datasets`, error envelope + `X-Correlation-ID` |
| T-1.2.1 | Keycloak realm `nla` với 3 tài khoản `admin`/`analyst`/`viewer`, Spring Security kiểm tra chữ ký JWT, `GET /api/v1/auth/session`, chặn quyền theo từng kho (bảng `warehouse_access`): không token → 401, sai kho → 403 |

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

Mật khẩu kho dữ liệu không lưu trong DB: bảng `warehouses` chỉ giữ `credential_reference` dạng `env:NLA_CHINOOK_PG_PASSWORD`, và API không bao giờ trả trường này. Backend không lưu mật khẩu người dùng: chỉ kiểm tra token do Keycloak ký.

## Phân quyền

| Vai trò | Kho được dùng (local) |
|---|---|
| `ADMIN` | Tất cả kho |
| `ANALYST` | Kho được cấp trong `warehouse_access` (local: cả 3 kho) |
| `VIEWER` | Kho được cấp trong `warehouse_access` (local: chỉ `wh-chinook-pg`) |

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

# 3. Chạy backend (cổng 8080)
cd backend
mvn spring-boot:run
```

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

Test tích hợp dùng Testcontainers (PostgreSQL 16) nên cần Docker đang chạy. Test phân quyền tự ký token RS256 và kiểm tra bằng đúng bộ validator của production (chữ ký, hạn, issuer, audience).
