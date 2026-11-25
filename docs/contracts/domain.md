# Canonical domain

JSON dùng camelCase; ID là opaque string ổn định, không parse ý nghĩa từ ID. Timestamp ISO-8601 UTC (`...Z`), date `YYYY-MM-DD`. Engine/dialect enum duy nhất: `POSTGRESQL | MYSQL | SQL_SERVER`. Số lượng/latency dùng integer không âm trong phạm vi an toàn JSON; confidence là number 0..1. Field `?` là optional; nullable phải ghi rõ. Không gửi secret qua DTO.

## Warehouse

| Field                         | Kiểu / ý nghĩa                                                                     |
| ----------------------------- | ---------------------------------------------------------------------------------- |
| warehouseId                   | string                                                                             |
| name                          | string                                                                             |
| engine                        | engine enum; authoritative source cho sqlDialect                                   |
| status                        | ACTIVE / UNAVAILABLE / INVALID / DISABLED                                          |
| host, database, defaultSchema | string; defaultSchema có thể null khi engine không áp dụng                         |
| port                          | integer 1..65535                                                                   |
| username                      | string; chỉ trả connection config cho admin                                        |
| credentialReference           | string; write-only trong create/update; không trả secret/reference vào public list |
| capabilities                  | `{readOnlyQuery:boolean, metadataDiscovery:boolean}`                               |
| createdAt, updatedAt          | timestamp                                                                          |

List/selector dùng safe summary `{warehouseId,name,engine,status,capabilities}`; admin detail có connection fields trừ credentialReference. Test/discover không trả connection string có password. ACTIVE nghĩa kết nối khả dụng, không thay cho authorization.

## Dataset

| Field                                     | Kiểu / ý nghĩa                                       |
| ----------------------------------------- | ---------------------------------------------------- |
| datasetId, warehouseId, name, description | string                                               |
| status                                    | ACTIVE / INVALID_SCHEMA / DISABLED                   |
| allowedSchemas                            | string[]                                             |
| allowedTables                             | TableRef[]; `{schema:string, name:string}`           |
| semanticVersion, schemaVersion            | string, snapshot identifier; không lấy version từ UI |
| updatedAt                                 | timestamp                                            |

Không dùng tên bảng không qualified làm security allowlist. Với MySQL, `schema` trong canonical TableRef là database/catalog. Metadata normalization/quoting theo adapter; không lowercase mọi identifier bất kể engine/case rules. Status dataset không phản ánh mọi trạng thái warehouse; phải kiểm tra cả hai.

## Query request

`{warehouseId:string,datasetId:string,question:string,mode?:AUTO|API|DETERMINISTIC,providerId?:string,followUpQueryId?:string}`. providerId chỉ hợp lệ cho API, chọn profile đã cấu hình; AUTO dùng mặc định, DETERMINISTIC không dùng provider. Default AUTO dùng provider server đã cấu hình; không ngầm chuyển deterministic khi provider lỗi. `question` trim, 1..1000 ký tự Unicode. Java suy ra dialect, allowed context, model và versions. Không chấp nhận client-supplied SQL/semanticContext/credential ở endpoint này.

`followUpQueryId` (T-4.2.2, US-4.2 AC2) là queryId của câu ngay trước khi người dùng hỏi nối tiếp; `question` chỉ chứa câu mới (ví dụ "chỉ lấy top 5"), FE không cần ghép câu cũ vào. Java kiểm tra trước khi tạo history:
- Câu trước phải xem được như `GET /query/{id}` (chủ query hoặc ADMIN, warehouse còn được cấp quyền): không tồn tại → `404 RESOURCE_NOT_FOUND`, không có quyền → `403 FORBIDDEN`.
- Câu trước phải cùng `datasetId` (do đó cùng warehouse) và có status `SUCCESS`; nếu không → `400 INVALID_REQUEST`. Chuỗi 1..200 ký tự, sai kiểu → 400.
- Hợp lệ thì Java gửi tóm tắt câu trước sang Python (`previousTurn`, xem [java-python.md](java-python.md)): câu hỏi, SQL cuối đã validate, giải thích, metric/dimension đã chọn. **Không gửi dòng kết quả.** SQL của câu trước đã gom ngữ cảnh của cả chuỗi nên chỉ cần câu liền trước.
- Query mới lưu `followUpQueryId`; chuỗi hội thoại được dựng lại bằng cách lần theo trường này. `semantic-query` không nhận trường này.

## Query (public detail)

| Field                                            | Kiểu / ý nghĩa                                                                                                                                                                                                                                                               |
| ------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| queryId, correlationId, warehouseId, datasetId   | string                                                                                                                                                                                                                                                                       |
| engine                                           | engine enum                                                                                                                                                                                                                                                                  |
| question                                         | string                                                                                                                                                                                                                                                                       |
| status                                           | RUNNING / SUCCESS / REJECTED / VALIDATION_ERROR / QUERY_ERROR / TIMEOUT                                                                                                                                                                                                      |
| candidateSql                                     | string hoặc null; output ban đầu của model/compiler, snapshot cũ default null                                                                                                                                                                                                |
| generatedSql                                     | string hoặc null nếu chưa sinh được; khi SUCCESS là SQL logic cuối được guard normalize/validate và chuyển executor; Query lỗi có thể giữ planned SQL trước khi guard đạt, không được gắn nhãn đã thực thi thành công; adapter có thể thêm envelope LIMIT hoặc cursor bounds |
| sqlDialect                                       | engine enum                                                                                                                                                                                                                                                                  |
| model                                            | string hoặc null trước khi xác định; model thực provider báo (fallback requested name nếu provider không trả)                                                                                                                                                                |
| provider                                         | nullable snapshot `{providerId,revision,name,kind,model,source}` theo [model providers](model-providers.md); model trong snapshot là requested model, không chứa key; snapshot cũ/offline/structured default null                                                            |
| executionMode                                    | API / DETERMINISTIC / SEMANTIC (structured metric selection; no LLM call)                                                                                                                                                                                                    |
| semanticVersion, schemaVersion                   | string, versions snapshot lúc chạy                                                                                                                                                                                                                                           |
| latencyMs                                        | integer hoặc null khi đang chạy                                                                                                                                                                                                                                              |
| createdAt, completedAt                           | timestamp; completedAt nullable                                                                                                                                                                                                                                              |
| followUpQueryId                                  | string hoặc null; queryId của câu trước khi đây là câu hỏi nối tiếp (T-4.2.2), snapshot cũ không có field này được hiểu là null |
| explanation                                      | string hoặc null                                                                                                                                                                                                                                                             |
| assumptions, selectedMetrics, selectedDimensions | string[], default []                                                                                                                                                                                                                                                         |
| sourceTables                                     | TableRef[]                                                                                                                                                                                                                                                                   |
| result                                           | QueryResult hoặc null khi lỗi/chưa xong                                                                                                                                                                                                                                      |
| chart                                            | ChartSpec hoặc null                                                                                                                                                                                                                                                          |
| error                                            | ErrorBody hoặc null                                                                                                                                                                                                                                                          |

Terminal Query immutable; “chạy lại” tạo queryId mới, không ghi đè history. Query detail từ history vẫn dùng snapshot cũ; chạy lại kiểm tra context hiện tại. Status phản ánh execution, không chỉ thành công HTTP hay LLM.

## QueryResult

```text
columns: [{name, label, dataType, semanticRole, unit?}]
rows: array of objects keyed by unique column name
returnedRowCount: integer      # tổng số hàng materialized trong snapshot có giới hạn
page: integer                 # 1-based
pageSize: integer             # 1..100, default 20
truncated: boolean            # engine result bị cắt bởi max rows/bytes
```

`dataType`: STRING, INTEGER, BIGINT, DECIMAL, FLOAT, BOOLEAN, DATE, TIMESTAMP. `semanticRole`: DIMENSION, METRIC, TIME, OTHER. DECIMAL và BIGINT truyền chuỗi thập phân để tránh mất precision; FLOAT là JSON number hữu hạn, không NaN/Infinity; NULL giữ null; BOOLEAN giữ boolean. TIMESTAMP chuẩn hóa UTC khi có timezone, cột không có timezone phải có quy tắc dataset công khai và Java chuyển đổi trước khi trả. Không bịa timezone.

Java tạo alias duy nhất nếu output có tên trùng, đồng thời bảo toàn label nguồn. `rows.length` là số dòng trang hiện tại, `returnedRowCount` là tổng snapshot có thể phân trang. Không gọi returnedRowCount là tổng thật trong warehouse khi truncated. Không bắt chạy COUNT(*) toàn query chỉ để hiển thị số tổng.

Kết quả được lưu snapshot có giới hạn; GET trang sau và CSV đọc cùng snapshot, không rerun SQL âm thầm. Khi snapshot hết hạn trả RESULT_EXPIRED. Export chỉ xuất snapshot được cấp quyền, không bypass max rows/bytes bằng nút “xuất toàn bộ”. UI phải thông báo phạm vi dữ liệu export.

## ChartSpec

`{type, title, x?:string, series: [{column:string,label:string}], reason?:string}`.

`type`: table / number / bar / grouped-bar / line / area / pie. `x` và series.column phải tồn tại trong result. table có series=[]; number có một metric scalar; bar/pie có dimension + metric; line/area ưu tiên time + metric; grouped-bar có dimension + nhiều metrics đơn vị tương thích. Không trộn tiền và số đơn hàng trên cùng một trục như một số ảnh minh họa.

Python recommendation dùng cùng cấu trúc nhưng Java quyết định chart cuối sau khi kiểm tra shape/type/units; không hợp lệ thì table với reason. FE validate allowlist lần nữa. Không executable JS, HTML/component hay callback từ AI.

Quy tắc Java đang áp dụng (T-4.3.2), cho cả `POST /query` và `semantic-query`:
- `Query.chart` của truy vấn SUCCESS luôn là ChartSpec dựng lại chỉ từ các field allowlist (`type,title,x?,series,reason?`); field lạ trong recommendation bị bỏ.
- Kết quả đúng 1 dòng × 1 cột số → `number` (thẻ KPI), bất kể AI gợi ý gì.
- `x` và `series[].column` được đối chiếu tên cột result (chính xác trước, sau đó không phân biệt hoa thường nếu chỉ khớp một cột) và trả về tên cột thật; thiếu cột, cột lặp, series không phải INTEGER/BIGINT/DECIMAL/FLOAT hoặc toàn NULL → `table`.
- Theo type: `number` cần 1 dòng + 1 series; `bar`/`pie` cần `x` + đúng 1 series; `pie` không nhận giá trị âm và tổng phải > 0; `grouped-bar` cần `x` + ≥2 series; `line`/`area` cần `x` + ≥1 series. Result rỗng, type ngoài allowlist hoặc không có recommendation → `table`.
- `title` rỗng hoặc dài hơn 500 ký tự → "Kết quả truy vấn"; `label` rỗng hoặc dài hơn 500 → label cột. `reason` là tiếng Việt, chỉ có khi phải chuyển về `table` (hoặc khi recommendation `table` tự mang reason hợp lệ ≤1000 ký tự).
- `semantic-query` không gọi AI: Java tự đề xuất trước khi kiểm tra như trên — không có dimension + 1 metric → `number`; 1 dimension TIME → `line`; 1 dimension khác → `bar` (1 metric) hoặc `grouped-bar` (nhiều metric); nhiều metric không dimension hoặc ≥2 dimension → `table` với reason.
- Chưa kiểm tra đơn vị vì result column chưa mang `unit`; khi adapter bổ sung `unit`, grouped-bar/line/area với đơn vị khác nhau phải chuyển `table`.

## List convention

Query params: `page=1&pageSize=20&sort=createdAt&direction=DESC`; pageSize tối đa 100. sort dùng allowlist từng endpoint, không nối trực tiếp SQL. Response `{items:[],page:1,pageSize:20,totalItems:0,totalPages:0}`; đây là count của resource list (registry/history), không tổng query warehouse. Stable sort có tie-breaker ID. Search là `search`, time filter UTC là `from`,`to` theo khoảng [from,to). Không tự giữ dataset cũ khi đổi warehouse.
