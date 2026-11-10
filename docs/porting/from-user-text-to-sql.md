# Port ứng dụng Text-to-SQL sang repository Hoang20444

Ngày làm việc: 10/11/2026. Nhánh: `module/port-user-tts-features`, tạo từ baseline `develop` của repository đích.

## Nguồn và cách port

- Repository đích: `Hoang20444/Text-to-SQL`, baseline `develop` tại `91f3211723d8e20b01ede1401885ed7ba74e93ec`. Baseline có Spring backend và data Chinook, nhưng `frontend/` mới có README; không có màn Query/Evaluation/Monitoring/Settings để mang sang.
- Nguồn ứng dụng: checkout `toannnnq1424/text-to-sql`, baseline `b4732f1`. Có mang theo sửa đổi local idempotent-registration ở `infra/local/compose.py` và test tương ứng, để khởi chạy lặp lại không ghi semantic model giống hệt và gặp HTTP 409 do quan hệ FK vật lý đã tự discovery.
- Thay thế source frontend/backend/AI/infra/data tương ứng trong nhánh mới; giữ nguyên `main` và lịch sử baseline target. Các file backlog `Project_Agile_Backlog_8_Sprints.*` của Hoang được giữ lại. Đây là một migration nhiều lớp vì bốn màn phụ thuộc routing/auth, contracts, query/result APIs, provider, schema và lưu trữ; không phải port UI độc lập.

## Mapping code và trách nhiệm

| Năng lực | Mã chạy trong nhánh port | Bên phục vụ và dữ liệu |
|---|---|---|
| Truy vấn | [`QueryPage.tsx`](../../frontend/src/pages/QueryPage.tsx), [`ResultPage.tsx`](../../frontend/src/pages/ResultPage.tsx), [`queryApi.ts`](../../frontend/src/api/queryApi.ts) | Java `QueryController`, `QueryService`, SQL guard và PostgreSQL/MySQL/SQL Server adapters; Python trả candidate; Ollama chạy local theo provider config. |
| Gợi ý mẫu | [`DatasetSuggestionService.java`](../../backend/src/main/java/dev/nla/DatasetSuggestionService.java), Chinook `semantic-*.json` | Gồm ví dụ duyệt trong semantic config và gợi ý theo schema active; câu hỏi mẫu không phải kết quả/SQL đã tạo sẵn. |
| Đánh giá | [`EvaluationPage.tsx`](../../frontend/src/pages/EvaluationPage.tsx), [`EvaluationController.java`](../../backend/src/main/java/dev/nla/EvaluationController.java) | Suite schema hiện tại 8 ca và Chinook regression 48 ca; ứng dụng gửi query qua API thật và giữ trạng thái lỗi/timeout riêng. |
| Giám sát | [`MonitoringPage.tsx`](../../frontend/src/pages/MonitoringPage.tsx), [`PlatformController.java`](../../backend/src/main/java/dev/nla/PlatformController.java) | Thống kê từ lịch sử query, truy vấn, warehouse và trạng thái API; trạng thái registry lưu không tương đương phép probe kết nối database trực tiếp. |
| Cài đặt | [`SettingsPage.tsx`](../../frontend/src/pages/SettingsPage.tsx), [`ModelProviderController.java`](../../backend/src/main/java/dev/nla/ModelProviderController.java) | Admin quản lý provider metadata/secret server-side; UI không trả plaintext API key. Ollama dùng model local, không cần key online. |
| SQL compiler/planner | [`app.py`](../../ai-service/app.py), semantic planner và session context | Java quyết định thực thi sau validation. Candidate, SQL đã rewrite, SQL thực thi và oracle đánh giá là các đối tượng khác nhau. |
| Kết nối an toàn | `backend/src/main/java/dev/nla/warehouse/`, `data-tool-runtime/` | Ba dialect có adapter thật; reader dùng quyền đọc. Các warehouse phải được đăng ký/kết nối, app không execute file SQL tùy ý. |
| Chinook / Docker | `data/chinook/`, `data/scripts/`, `infra/compose*.yml`, `infra/local/compose.py` | Provision PostgreSQL, MySQL, SQL Server; local secrets nằm trong `.local/`, không commit. |

## Source reuse và attribution

Snapshot được giữ cùng license/notice tại [`vendor/upstream/`](../../vendor/upstream/README.md). Báo cáo [runtime usage](../source-fork-runtime-usage.md), [full source adoption](../integrations/full-source-adoption.md) và [platform reuse](../platform-reuse.md) tách phần Wren Engine (`Canner/wren-engine`) và MCP Toolbox for Databases (`googleapis/mcp-toolbox`) khỏi phần adapter/ứng dụng do dự án gọi. Source có trong repo không có nghĩa mọi connector của upstream được hỗ trợ.

## Định lượng gói ví dụ

Mỗi `data/chinook/semantic-{postgresql,mysql,mssql}.json` có 46 ví dụ đã cấu hình. Các file `data/chinook/evaluation-{postgresql,mysql,mssql}.json` cung cấp 48 ca regression mỗi engine; suite `dataset-schema` có 8 ca trên schema runtime. Vì corpus regression có ví dụ đã dùng trong semantic config, 48 ca này là regression/integration suite, không phải holdout độc lập để tuyên bố độ chính xác tổng quát của model.

## Kiểm chứng và trạng thái

Kết quả trên nhánh port ngày 10/11/2026:

- FE: 27 test files, 221 tests PASS; TypeScript/Vite production build PASS. Vitest ghi Recharts size warnings trong jsdom; không phải test failure. Browser visual QA/recording chưa chạy.
- BE: Maven test PASS, 175 tests, 0 failures, 0 errors, 14 skipped.
- AI/infra: pytest `ai-service` + `infra/local/test_compose.py`: 470 passed, 1 skipped. `infra/local/compose.py up` và `verify` PASS; `git diff --check` PASS.
- Chinook thật: PostgreSQL/MySQL/SQL Server đều release 1.4.5; mỗi engine có 11 bảng, Album 347, Track 3503; reader write denied.
- Ollama thật: model `qwen3:4b`, mode AUTO, 3/3 paraphrase tiếng Việt thành công qua Java → Python → Ollama → planner → database; mỗi query trả kết quả oracle hợp lệ. Thời gian 14.042s PostgreSQL, 9.458s MySQL, 9.681s SQL Server. API key online không được gọi.
- Mỗi dataset trả 89 gợi ý qua API thật: 46 CURATED, 35 PHYSICAL_SCHEMA, 8 SEMANTIC_MODEL. Có 2 evaluation suites/dataset: `dataset-schema` (8 ca) và `chinook-core` (48 ca). Monitoring trả dữ liệu history thật trong lượt QA, 12 truy vấn/12 mẫu.
- `http://127.0.0.1:4173/` trả HTTP 200; `/api/v1/health` trả HTTP 200. Tất cả container frontend/backend/Python/file service/executor, IdP và ba database healthy.

Lần boot đầu dừng ở bước provision vì clone đích không có provenance marker cục bộ; không có database nào bị ghi đè. Đã đồng bộ ignored `.local` đã provision của môi trường hiện hữu, giữ nguyên volume Chinook rồi khởi động lại thành công. Evidence chi tiết và giới hạn ghi trong [`work-log.md`](../work-log.md). Chỉ đánh dấu PASS cho các lệnh/đường gọi đã chạy trên nhánh port. Browser visual QA/recording, toàn bộ routes bằng thao tác người dùng và localization review chưa chạy; không coi source copy, giao diện render, container health hoặc API contract riêng lẻ là bằng chứng đủ cho các phần đó.
