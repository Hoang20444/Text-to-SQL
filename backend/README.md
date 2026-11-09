# Backend Service (Java 21 + Spring Boot)

Thư mục dành cho thành viên Backend triển khai theo Sprint Backlog (`docs/Project_Agile_Backlog_8_Sprints.xlsx`).

## Đã có (Sprint 1)

| Task | Nội dung |
|---|---|
| T-1.1.1 | Spring Boot 3.5 + Flyway, Metadata DB (`warehouses`, `datasets`), `GET /api/v1/warehouses`, `GET /api/v1/datasets`, error envelope + `X-Correlation-ID` |

## Cấu hình

Backend đọc cấu hình từ biến môi trường hoặc file `.local/docker.env` (do `data/scripts/docker_chinook.py` sinh ra, đã bị Git ignore):

| Biến | Ý nghĩa |
|---|---|
| `METADATA_JDBC_URL`, `METADATA_USERNAME`, `METADATA_PASSWORD` | Metadata DB `nla_metadata` (tài khoản `nla_app`) |
| `CHINOOK_DOCKER=true` | Tự đăng ký 3 kho Chinook local (PostgreSQL, MySQL, SQL Server) khi khởi động |
| `NLA_MYSQL_PORT` | Cổng MySQL nếu khác `53306` |
| `NLA_DATA_ROOT` | Đường dẫn tới thư mục `data/` của repo. Bỏ trống thì tự tìm `./data` hoặc `../data`, nên chạy từ gốc repo hay từ `backend/` đều được |

Mật khẩu kho dữ liệu không lưu trong DB: bảng `warehouses` chỉ giữ `credential_reference` dạng `env:NLA_CHINOOK_PG_PASSWORD`, và API không bao giờ trả trường này.

## Chạy

```bash
# 1. Dựng 3 CSDL và nạp Chinook (một lần)
python data/scripts/docker_chinook.py init
python data/scripts/docker_chinook.py up
python data/scripts/docker_chinook.py provision

# 2. Chạy backend (cổng 8080)
cd backend
mvn spring-boot:run
```

Kiểm tra nhanh:

```bash
curl http://localhost:8080/api/v1/warehouses
curl "http://localhost:8080/api/v1/datasets?warehouseId=wh-chinook-pg"
```

## Test

```bash
cd backend
mvn test
```

Test tích hợp dùng Testcontainers (PostgreSQL 16) nên cần Docker đang chạy.
