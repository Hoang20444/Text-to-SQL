"""Sprint 4 tests for unaccented Vietnamese FK bridging, follow-up context, and chart alignment."""

import json
from copy import deepcopy
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from app import Candidate, Chart, Input, SemanticContextSelector, app, validate
from check_memory_runtime import check as check_lancedb_runtime

ROOT = Path(__file__).resolve().parents[2]
TOKEN = "sprint4-followup-and-chart-test-token-32chars"


def _chinook_context(engine: str) -> dict:
    suffix = {"POSTGRESQL": "postgresql", "MYSQL": "mysql", "SQL_SERVER": "mssql"}[engine]
    schema = {"POSTGRESQL": "public", "MYSQL": "nla_chinook", "SQL_SERVER": "dbo"}[engine]
    pg = engine == "POSTGRESQL"
    ctx = json.loads((ROOT / f"data/chinook/semantic-{suffix}.json").read_text(encoding="utf-8"))

    def tbl(pg_name: str, pascal_name: str) -> str:
        return pg_name if pg else pascal_name

    def col(pg_name: str, pascal_name: str) -> str:
        return pg_name if pg else pascal_name

    def c(pg_name: str, pascal_name: str, dtype: str, syns: list[str] | None = None) -> dict:
        entry: dict = {"name": col(pg_name, pascal_name), "dataType": dtype}
        if syns:
            entry["synonyms"] = syns
        return entry

    ctx["tables"] = [
        {
            "schema": schema,
            "name": tbl("artist", "Artist"),
            "logicalName": "Nghệ sĩ",
            "description": "Danh sách nghệ sĩ (nghe si, ca si)",
            "columns": [
                c("artist_id", "ArtistId", "INTEGER"),
                c("name", "Name", "STRING", ["nghệ sĩ", "nghe si", "ca sĩ"]),
            ],
        },
        {
            "schema": schema,
            "name": tbl("album", "Album"),
            "logicalName": "Album",
            "columns": [
                c("album_id", "AlbumId", "INTEGER"),
                c("title", "Title", "STRING"),
                c("artist_id", "ArtistId", "INTEGER"),
            ],
        },
        {
            "schema": schema,
            "name": tbl("track", "Track"),
            "logicalName": "Bài hát",
            "columns": [
                c("track_id", "TrackId", "INTEGER"),
                c("name", "Name", "STRING", ["bài hát", "bản nhạc"]),
                c("album_id", "AlbumId", "INTEGER"),
                c("genre_id", "GenreId", "INTEGER"),
                c("media_type_id", "MediaTypeId", "INTEGER"),
                c("unit_price", "UnitPrice", "DECIMAL"),
            ],
        },
        {
            "schema": schema,
            "name": tbl("genre", "Genre"),
            "logicalName": "Thể loại",
            "columns": [
                c("genre_id", "GenreId", "INTEGER"),
                c("name", "Name", "STRING", ["thể loại", "the loai"]),
            ],
        },
        {
            "schema": schema,
            "name": tbl("media_type", "MediaType"),
            "logicalName": "Định dạng",
            "columns": [c("media_type_id", "MediaTypeId", "INTEGER"), c("name", "Name", "STRING")],
        },
        {
            "schema": schema,
            "name": tbl("invoice_line", "InvoiceLine"),
            "logicalName": "Chi tiết hóa đơn",
            "columns": [
                c("invoice_line_id", "InvoiceLineId", "INTEGER"),
                c("invoice_id", "InvoiceId", "INTEGER"),
                c("track_id", "TrackId", "INTEGER"),
                c("unit_price", "UnitPrice", "DECIMAL"),
                c("quantity", "Quantity", "INTEGER"),
            ],
        },
        {
            "schema": schema,
            "name": tbl("invoice", "Invoice"),
            "logicalName": "Hóa đơn",
            "columns": [
                c("invoice_id", "InvoiceId", "INTEGER"),
                c("customer_id", "CustomerId", "INTEGER"),
                c("invoice_date", "InvoiceDate", "TIMESTAMP"),
                c("billing_country", "BillingCountry", "STRING"),
                c("total", "Total", "DECIMAL"),
            ],
        },
        {
            "schema": schema,
            "name": tbl("customer", "Customer"),
            "logicalName": "Khách hàng",
            "columns": [
                c("customer_id", "CustomerId", "INTEGER"),
                c("first_name", "FirstName", "STRING"),
                c("last_name", "LastName", "STRING"),
                c("country", "Country", "STRING"),
                c("support_rep_id", "SupportRepId", "INTEGER"),
            ],
        },
        {
            "schema": schema,
            "name": tbl("employee", "Employee"),
            "logicalName": "Nhân viên",
            "columns": [
                c("employee_id", "EmployeeId", "INTEGER"),
                c("last_name", "LastName", "STRING"),
            ],
        },
        {
            "schema": schema,
            "name": tbl("playlist", "Playlist"),
            "logicalName": "Playlist",
            "columns": [c("playlist_id", "PlaylistId", "INTEGER"), c("name", "Name", "STRING")],
        },
        {
            "schema": schema,
            "name": tbl("playlist_track", "PlaylistTrack"),
            "logicalName": "Playlist Track",
            "columns": [
                c("playlist_id", "PlaylistId", "INTEGER"),
                c("track_id", "TrackId", "INTEGER"),
            ],
        },
    ]

    def rel(
        from_tbl: tuple[str, str],
        from_col: tuple[str, str],
        to_tbl: tuple[str, str],
        to_col: tuple[str, str],
    ) -> dict:
        return {
            "from": {"table": {"schema": schema, "name": tbl(*from_tbl)}, "column": col(*from_col)},
            "to": {"table": {"schema": schema, "name": tbl(*to_tbl)}, "column": col(*to_col)},
            "cardinality": "MANY_TO_ONE",
        }

    ctx["relationships"] = [
        rel(
            ("album", "Album"),
            ("artist_id", "ArtistId"),
            ("artist", "Artist"),
            ("artist_id", "ArtistId"),
        ),
        rel(
            ("track", "Track"), ("album_id", "AlbumId"), ("album", "Album"), ("album_id", "AlbumId")
        ),
        rel(
            ("track", "Track"), ("genre_id", "GenreId"), ("genre", "Genre"), ("genre_id", "GenreId")
        ),
        rel(
            ("track", "Track"),
            ("media_type_id", "MediaTypeId"),
            ("media_type", "MediaType"),
            ("media_type_id", "MediaTypeId"),
        ),
        rel(
            ("invoice_line", "InvoiceLine"),
            ("track_id", "TrackId"),
            ("track", "Track"),
            ("track_id", "TrackId"),
        ),
        rel(
            ("invoice_line", "InvoiceLine"),
            ("invoice_id", "InvoiceId"),
            ("invoice", "Invoice"),
            ("invoice_id", "InvoiceId"),
        ),
        rel(
            ("invoice", "Invoice"),
            ("customer_id", "CustomerId"),
            ("customer", "Customer"),
            ("customer_id", "CustomerId"),
        ),
        rel(
            ("customer", "Customer"),
            ("support_rep_id", "SupportRepId"),
            ("employee", "Employee"),
            ("employee_id", "EmployeeId"),
        ),
        rel(
            ("playlist_track", "PlaylistTrack"),
            ("playlist_id", "PlaylistId"),
            ("playlist", "Playlist"),
            ("playlist_id", "PlaylistId"),
        ),
        rel(
            ("playlist_track", "PlaylistTrack"),
            ("track_id", "TrackId"),
            ("track", "Track"),
            ("track_id", "TrackId"),
        ),
    ]
    return ctx


def test_lancedb_native_vector_runtime_executes_locally():
    check_lancedb_runtime()


@pytest.mark.parametrize("engine", ["POSTGRESQL", "MYSQL", "SQL_SERVER"])
def test_unaccented_vietnamese_preserves_multihop_fk_bridges_across_engines(engine):
    context = _chinook_context(engine)
    request = Input.model_validate(
        {
            "correlationId": f"s4-bridge-{engine.lower()}",
            "warehouseId": "wh-chinook",
            "datasetId": "ds-chinook",
            "sqlDialect": engine,
            "question": "doanh thu theo nghe si",
            "semanticContext": context,
        }
    )
    selected = SemanticContextSelector().select(request)
    selected_names = {table["name"].casefold() for table in selected["tables"]}
    assert {
        "artist",
        "album",
        "track",
        "invoice_line" if engine == "POSTGRESQL" else "invoiceline",
    } <= selected_names
    assert len(selected["tables"]) < len(context["tables"])
    assert len(selected["relationships"]) >= 3


@pytest.mark.parametrize("engine", ["POSTGRESQL", "MYSQL", "SQL_SERVER"])
def test_followup_question_retains_parent_anchors_and_bridges(engine):
    context = _chinook_context(engine)
    # Include distractor columns named "question" and "context" in an unrelated table to verify
    # structural follow-up prefixes never trigger false table matches.
    context["tables"].append(
        {
            "schema": "dbo" if engine == "SQL_SERVER" else "public",
            "name": "audit_log",
            "columns": [
                {"name": "id", "dataType": "INTEGER"},
                {"name": "question", "dataType": "STRING"},
                {"name": "context", "dataType": "STRING"},
            ],
        }
    )
    followup = (
        "Previous question context: doanh thu theo nghe si?\n" "Follow-up question: chi lay top 5"
    )
    request = Input.model_validate(
        {
            "correlationId": f"s4-followup-{engine.lower()}",
            "warehouseId": "wh-chinook",
            "datasetId": "ds-chinook",
            "sqlDialect": engine,
            "question": followup,
            "semanticContext": context,
        }
    )
    selected = SemanticContextSelector().select(request)
    selected_names = {table["name"].casefold() for table in selected["tables"]}
    assert "audit_log" not in selected_names
    assert "artist" in selected_names
    assert "album" in selected_names


def test_provider_system_message_and_client_support_followup_with_chart_alignment(monkeypatch):
    monkeypatch.setenv("PYTHON_AI_TOKEN", TOKEN)
    monkeypatch.setenv("LLM_BASE_URL", "http://localhost:11434/v1")
    monkeypatch.setenv("LLM_MODEL", "qwen3:4b")
    monkeypatch.setenv("LLM_API_KEY", "test-key-not-leaked")
    monkeypatch.setenv("KNOWLEDGE_ENABLED", "false")

    captured = []
    context = _chinook_context("POSTGRESQL")

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        captured.append(body)
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "sql": (
                                        'SELECT a."name" AS artist_name, '
                                        'SUM(il."unit_price" * il."quantity") AS net_revenue '
                                        'FROM "public"."artist" a '
                                        'JOIN "public"."album" al ON al."artist_id" = a."artist_id" '
                                        'JOIN "public"."track" t ON t."album_id" = al."album_id" '
                                        'JOIN "public"."invoice_line" il ON il."track_id" = t."track_id" '
                                        'GROUP BY a."artist_id", a."name" '
                                        "ORDER BY net_revenue DESC LIMIT 5"
                                    ),
                                    "explanation": "Top 5 nghệ sĩ theo doanh thu thuần từ câu hỏi trước.",
                                    "assumptions": [],
                                    "selectedMetrics": [],
                                    "selectedDimensions": [],
                                    "chartRecommendation": {
                                        "type": "bar",
                                        "title": "Top 5 nghệ sĩ theo doanh thu",
                                        "x": "artist_name",
                                        "series": [{"column": "net_revenue", "label": "Doanh thu"}],
                                    },
                                    "confidence": 0.92,
                                    "model": "qwen3:4b",
                                }
                            )
                        }
                    }
                ]
            },
        )

    app.state.provider_transport = httpx.MockTransport(handler)
    try:
        with TestClient(app) as client:
            res = client.post(
                "/internal/v1/text-to-sql",
                json={
                    "correlationId": "s4-followup-api",
                    "warehouseId": "wh-chinook",
                    "datasetId": "ds-chinook",
                    "sqlDialect": "POSTGRESQL",
                    "question": (
                        "Previous question context: doanh thu theo nghe si?\n"
                        "Follow-up question: chi lay top 5"
                    ),
                    "semanticContext": context,
                },
                headers={
                    "Authorization": f"Bearer {TOKEN}",
                    "X-Correlation-ID": "s4-followup-api",
                    "X-Execution-Mode": "API",
                },
            )
            assert res.status_code == 200, res.text
            assert "Previous question context:" in captured[0]["messages"][0]["content"]
            assert res.json()["chartRecommendation"] == {
                "type": "bar",
                "title": "Top 5 nghệ sĩ theo doanh thu",
                "x": "artist_name",
                "series": [{"column": "net_revenue", "label": "Doanh thu"}],
            }
    finally:
        if hasattr(app.state, "provider_transport"):
            del app.state.provider_transport


@pytest.mark.parametrize("engine", ["POSTGRESQL", "MYSQL", "SQL_SERVER"])
@pytest.mark.parametrize(
    ("chart_type", "sql", "chart"),
    [
        (
            "number",
            "SELECT COUNT(*) AS track_count FROM {track}",
            {
                "type": "number",
                "title": "Tổng số bản nhạc",
                "series": [{"column": "track_count", "label": "Số bản nhạc"}],
            },
        ),
        (
            "line",
            "SELECT {month_expr} AS billing_month, SUM({total_col}) AS revenue FROM {invoice} GROUP BY {month_expr} ORDER BY billing_month",
            {
                "type": "line",
                "title": "Doanh thu theo tháng",
                "x": "billing_month",
                "series": [{"column": "revenue", "label": "Doanh thu"}],
            },
        ),
        (
            "grouped-bar",
            "SELECT {country_col} AS billing_country, SUM({total_col}) AS revenue, COUNT(*) AS invoice_count FROM {invoice} GROUP BY {country_col}",
            {
                "type": "grouped-bar",
                "title": "Doanh thu và số hóa đơn theo quốc gia",
                "x": "billing_country",
                "series": [
                    {"column": "revenue", "label": "Doanh thu"},
                    {"column": "invoice_count", "label": "Số hóa đơn"},
                ],
            },
        ),
    ],
)
def test_chart_recommendation_validates_aliases_for_all_dialects(engine, chart_type, sql, chart):
    context = _chinook_context(engine)
    if engine == "POSTGRESQL":
        fmt = {
            "track": '"public"."track"',
            "invoice": '"public"."invoice"',
            "month_expr": 'EXTRACT(MONTH FROM "invoice_date")',
            "total_col": '"total"',
            "country_col": '"billing_country"',
        }
    elif engine == "MYSQL":
        fmt = {
            "track": "`nla_chinook`.`Track`",
            "invoice": "`nla_chinook`.`Invoice`",
            "month_expr": "MONTH(`InvoiceDate`)",
            "total_col": "`Total`",
            "country_col": "`BillingCountry`",
        }
    else:
        fmt = {
            "track": "[dbo].[Track]",
            "invoice": "[dbo].[Invoice]",
            "month_expr": "MONTH([InvoiceDate])",
            "total_col": "[Total]",
            "country_col": "[BillingCountry]",
        }
    formatted_sql = sql.format(**fmt)
    candidate = Candidate(
        sql=formatted_sql,
        explanation="Chart alignment check",
        assumptions=[],
        selectedMetrics=[],
        selectedDimensions=[],
        chartRecommendation=Chart.model_validate(chart),
        confidence=0.9,
        model="qwen3:4b",
    )
    validated = validate(deepcopy(candidate), context, engine)
    assert validated.chartRecommendation.type == chart_type
    assert validated.chartRecommendation.series[0].column == chart["series"][0]["column"]
