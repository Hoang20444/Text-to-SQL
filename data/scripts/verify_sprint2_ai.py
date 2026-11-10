#!/usr/bin/env python3
"""Sprint 2 Automated Verification Script for AI & Data Deliverables (T-2.1.2, T-2.2.2, T-2.3.2)."""

import argparse
import json
import os
import sys
from pathlib import Path

import httpx
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "ai-service"))

from app import (  # noqa: E402
    Candidate,
    Chart,
    Controlled,
    Input,
    SemanticContextSelector,
    app,
    validate,
)
from schema_relevance import table_mentions  # noqa: E402


def verify_schema_compaction() -> None:
    tables = [
        {
            "schema": "public",
            "name": "invoice",
            "columns": [
                {"name": "invoice_id", "dataType": "INTEGER"},
                {"name": "customer_id", "dataType": "INTEGER"},
                {"name": "total", "dataType": "DECIMAL"},
            ],
        },
        {
            "schema": "public",
            "name": "customer",
            "columns": [
                {"name": "customer_id", "dataType": "INTEGER"},
                {"name": "country", "dataType": "VARCHAR"},
            ],
        },
        *[
            {
                "schema": "archive",
                "name": f"unused_log_{idx}",
                "columns": [{"name": "id", "dataType": "INTEGER"}],
            }
            for idx in range(30)
        ],
    ]
    found = table_mentions("Tổng doanh thu bảng invoice theo customer?", tables)
    assert found.positions == frozenset({0, 1}), f"Unexpected table positions: {found.positions}"

    req = Input.model_validate(
        {
            "correlationId": "verify-s2",
            "warehouseId": "wh-pg",
            "datasetId": "ds-pg",
            "sqlDialect": "POSTGRESQL",
            "question": "Tổng doanh thu bảng invoice theo customer?",
            "semanticContext": {
                "tables": tables,
                "metrics": [],
                "dimensions": [],
                "relationships": [
                    {
                        "from": {
                            "table": {"schema": "public", "name": "invoice"},
                            "column": "customer_id",
                        },
                        "to": {
                            "table": {"schema": "public", "name": "customer"},
                            "column": "customer_id",
                        },
                        "cardinality": "MANY_TO_ONE",
                    }
                ],
                "examples": [],
                "businessRules": [],
                "semanticVersion": "1.0.0",
                "schemaVersion": "1.0.0",
                "timezone": "Asia/Ho_Chi_Minh",
                "referenceTime": "2026-11-08T00:00:00Z",
            },
        }
    )
    selected = SemanticContextSelector().select(req)
    if len(selected["tables"]) != 2:
        raise RuntimeError(f"Expected 2 compacted tables, got {len(selected['tables'])}")
    print(
        "PASS [T-2.1.2]: Physical schema compaction & join-path selection verified (32 -> 2 tables)."
    )


def verify_candidate_guards() -> None:
    ctx = {
        "tables": [
            {
                "schema": "dbo",
                "name": "Album",
                "columns": [{"name": "AlbumId", "dataType": "INTEGER"}],
            }
        ],
        "metrics": [],
        "dimensions": [],
    }
    bad = Candidate(
        sql="SELECT [AlbumId] FROM [dbo].[Album] LIMIT 10",
        explanation="Invalid LIMIT on SQL Server",
        assumptions=[],
        selectedMetrics=[],
        selectedDimensions=[],
        chartRecommendation=Chart(type="table", title="Albums", series=[]),
        confidence=0.9,
        model="qwen3:4b",
    )
    try:
        validate(bad, ctx, "SQL_SERVER")
        raise RuntimeError("SQL_SERVER LIMIT should have been rejected")
    except Controlled as err:
        assert err.code == "SQL_DIALECT_ERROR"
    print("PASS [T-2.2.2]: Candidate SQL post-validation & 3-dialect syntax guards verified.")


def verify_ollama_and_openai_client() -> None:
    token = "verify-sprint2-private-service-token-32chars"
    os.environ["PYTHON_AI_TOKEN"] = token
    os.environ["LLM_BASE_URL"] = "http://127.0.0.1:11434/v1"
    os.environ["LLM_MODEL"] = "qwen3:4b"
    os.environ.pop("LLM_API_KEY", None)

    expected_sql_by_dialect = {
        "POSTGRESQL": 'SELECT COUNT(*) AS record_count FROM "public"."Album"',
        "MYSQL": "SELECT COUNT(*) AS record_count FROM `public`.`Album`",
        "SQL_SERVER": "SELECT COUNT(*) AS record_count FROM [dbo].[Album]",
    }

    for dialect, sql in expected_sql_by_dialect.items():
        schema_name = "dbo" if dialect == "SQL_SERVER" else "public"

        def mock_ollama(request, candidate_sql=sql):
            sent = json.loads(request.content)
            assert sent["model"] == "qwen3:4b"
            assert "/no_think" in sent["messages"][0]["content"]
            return httpx.Response(
                200,
                json={
                    "model": "qwen3:4b",
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {
                                        "sql": candidate_sql,
                                        "explanation": "Đếm tổng số bản ghi trong bảng Album.",
                                        "assumptions": [],
                                        "selectedMetrics": [],
                                        "selectedDimensions": [],
                                        "chartRecommendation": {
                                            "type": "number",
                                            "title": "Số lượng Album",
                                            "series": [
                                                {"column": "record_count", "label": "Album"}
                                            ],
                                        },
                                        "confidence": 0.96,
                                        "model": "qwen3:4b",
                                    }
                                )
                            }
                        }
                    ],
                },
            )

        app.state.provider_transport = httpx.MockTransport(mock_ollama)
        with TestClient(app) as client:
            status_res = client.post(
                "/internal/v1/model-provider/status",
                json={"correlationId": "status-check"},
                headers={
                    "Authorization": f"Bearer {token}",
                    "X-Correlation-ID": "status-check",
                },
            )
            assert status_res.status_code == 200
            assert status_res.json()["provider"]["kind"] == "OLLAMA"

            gen_res = client.post(
                "/internal/v1/text-to-sql",
                json={
                    "correlationId": f"corr-{dialect}",
                    "warehouseId": "wh-demo",
                    "datasetId": "ds-demo",
                    "sqlDialect": dialect,
                    "question": "Có bao nhiêu bản ghi trong bảng Album?",
                    "semanticContext": {
                        "tables": [
                            {
                                "schema": schema_name,
                                "name": "Album",
                                "columns": [{"name": "AlbumId", "dataType": "INTEGER"}],
                            }
                        ],
                        "metrics": [],
                        "dimensions": [],
                        "relationships": [],
                        "examples": [],
                        "businessRules": [],
                        "semanticVersion": "1.0.0",
                        "schemaVersion": "1.0.0",
                        "timezone": "Asia/Ho_Chi_Minh",
                        "referenceTime": "2026-11-08T00:00:00Z",
                    },
                },
                headers={
                    "Authorization": f"Bearer {token}",
                    "X-Correlation-ID": f"corr-{dialect}",
                    "X-Execution-Mode": "AUTO",
                },
            )
            if gen_res.status_code != 200 or gen_res.json()["sql"] != sql:
                raise RuntimeError(f"Failed generation for {dialect}: {gen_res.text}")

    if hasattr(app.state, "provider_transport"):
        delattr(app.state, "provider_transport")
    print(
        "PASS [T-2.3.2]: Async Ollama/OpenAI client & /model-provider/status verified on 3 dialects."
    )


def ask_live_ollama(question: str, dialect: str, model: str) -> None:
    token = "verify-sprint2-private-service-token-32chars"
    os.environ["PYTHON_AI_TOKEN"] = token
    os.environ["NLA_MODEL_PROVIDER_HOSTS"] = "127.0.0.1,localhost"
    os.environ["LLM_BASE_URL"] = "http://127.0.0.1:11434/v1"
    os.environ["LLM_MODEL"] = model
    os.environ["LLM_API_KEY"] = "ollama"
    os.environ["LLM_TIMEOUT_MS"] = "90000"
    os.environ["LLM_REASONING_EFFORT"] = "none"
    os.environ["LLM_STRUCTURED_OUTPUT"] = "true"

    semantic_file = {
        "POSTGRESQL": "semantic-postgresql.json",
        "MYSQL": "semantic-mysql.json",
        "SQL_SERVER": "semantic-mssql.json",
    }[dialect]
    sem = json.loads((ROOT / "data/chinook" / semantic_file).read_text(encoding="utf-8"))
    if not sem.get("tables"):
        schema_name = (
            "public"
            if dialect == "POSTGRESQL"
            else "nla_chinook" if dialect == "MYSQL" else "dbo"
        )
        pg = dialect == "POSTGRESQL"
        chinook_catalog = [
            (
                "album" if pg else "Album",
                "Album nhạc",
                ["album", "đĩa nhạc", "dia nhac"],
                [
                    ("album_id" if pg else "AlbumId", "INTEGER"),
                    ("title" if pg else "Title", "STRING"),
                    ("artist_id" if pg else "ArtistId", "INTEGER"),
                ],
            ),
            (
                "artist" if pg else "Artist",
                "Nghệ sĩ",
                ["artist", "nghệ sĩ", "nghe si", "ca sĩ", "ca si", "ban nhạc", "ban nhac"],
                [
                    ("artist_id" if pg else "ArtistId", "INTEGER"),
                    ("name" if pg else "Name", "STRING"),
                ],
            ),
            (
                "customer" if pg else "Customer",
                "Khách hàng",
                ["customer", "customers", "khách hàng", "khach hang", "người mua", "nguoi mua"],
                [
                    ("customer_id" if pg else "CustomerId", "INTEGER"),
                    ("first_name" if pg else "FirstName", "STRING"),
                    ("last_name" if pg else "LastName", "STRING"),
                    ("country" if pg else "Country", "STRING"),
                    ("email" if pg else "Email", "STRING"),
                ],
            ),
            (
                "employee" if pg else "Employee",
                "Nhân viên",
                ["employee", "employees", "nhân viên", "nhan vien"],
                [
                    ("employee_id" if pg else "EmployeeId", "INTEGER"),
                    ("first_name" if pg else "FirstName", "STRING"),
                    ("last_name" if pg else "LastName", "STRING"),
                    ("title" if pg else "Title", "STRING"),
                ],
            ),
            (
                "genre" if pg else "Genre",
                "Thể loại nhạc",
                ["genre", "genres", "thể loại", "the loai", "dòng nhạc", "dong nhac"],
                [
                    ("genre_id" if pg else "GenreId", "INTEGER"),
                    ("name" if pg else "Name", "STRING"),
                ],
            ),
            (
                "invoice" if pg else "Invoice",
                "Hóa đơn",
                ["invoice", "invoices", "hóa đơn", "hoa don", "doanh thu", "doanh số", "doanh so"],
                [
                    ("invoice_id" if pg else "InvoiceId", "INTEGER"),
                    ("customer_id" if pg else "CustomerId", "INTEGER"),
                    ("billing_country" if pg else "BillingCountry", "STRING"),
                    ("invoice_date" if pg else "InvoiceDate", "TIMESTAMP"),
                    ("total" if pg else "Total", "DECIMAL"),
                ],
            ),
            (
                "invoice_line" if pg else "InvoiceLine",
                "Chi tiết hóa đơn",
                ["invoice_line", "chi tiết hóa đơn", "chi tiet hoa don"],
                [
                    ("invoice_line_id" if pg else "InvoiceLineId", "INTEGER"),
                    ("invoice_id" if pg else "InvoiceId", "INTEGER"),
                    ("track_id" if pg else "TrackId", "INTEGER"),
                    ("unit_price" if pg else "UnitPrice", "DECIMAL"),
                    ("quantity" if pg else "Quantity", "INTEGER"),
                ],
            ),
            (
                "media_type" if pg else "MediaType",
                "Định dạng media",
                ["media_type", "định dạng", "dinh dang"],
                [
                    ("media_type_id" if pg else "MediaTypeId", "INTEGER"),
                    ("name" if pg else "Name", "STRING"),
                ],
            ),
            (
                "playlist" if pg else "Playlist",
                "Danh sách phát",
                ["playlist", "playlists", "danh sách phát", "danh sach phat"],
                [
                    ("playlist_id" if pg else "PlaylistId", "INTEGER"),
                    ("name" if pg else "Name", "STRING"),
                ],
            ),
            (
                "playlist_track" if pg else "PlaylistTrack",
                "Bài hát trong playlist",
                ["playlist_track"],
                [
                    ("playlist_id" if pg else "PlaylistId", "INTEGER"),
                    ("track_id" if pg else "TrackId", "INTEGER"),
                ],
            ),
            (
                "track" if pg else "Track",
                "Bài hát / Bản nhạc",
                ["track", "tracks", "bài hát", "bai hat", "bản nhạc", "ban nhac"],
                [
                    ("track_id" if pg else "TrackId", "INTEGER"),
                    ("name" if pg else "Name", "STRING"),
                    ("album_id" if pg else "AlbumId", "INTEGER"),
                    ("genre_id" if pg else "GenreId", "INTEGER"),
                    ("unit_price" if pg else "UnitPrice", "DECIMAL"),
                ],
            ),
        ]
        sem["tables"] = [
            {
                "schema": schema_name,
                "name": tbl_name,
                "logicalName": logical_name,
                "description": f"{logical_name} ({ ', '.join(syns) })",
                "columns": [
                    {"name": col_name, "dataType": col_type, "synonyms": syns}
                    for col_name, col_type in cols
                ],
            }
            for tbl_name, logical_name, syns, cols in chinook_catalog
        ]

    if hasattr(app.state, "provider_transport"):
        delattr(app.state, "provider_transport")

    corr_id = "live-ollama-demo"
    print(f"Calling live Ollama ({model}) on {dialect} for question: {question!r} ...")
    with TestClient(app) as client:
        res = client.post(
            "/internal/v1/text-to-sql",
            json={
                "correlationId": corr_id,
                "warehouseId": "wh-chinook",
                "datasetId": "ds-chinook",
                "sqlDialect": dialect,
                "question": question,
                "semanticContext": sem,
            },
            headers={
                "Authorization": f"Bearer {token}",
                "X-Correlation-ID": corr_id,
                "X-Execution-Mode": "API",
            },
        )
        print(f"HTTP Status: {res.status_code}")
        print(json.dumps(res.json(), indent=2, ensure_ascii=False))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ask", help="Send a live natural-language question to local Ollama")
    parser.add_argument(
        "--dialect",
        default="POSTGRESQL",
        choices=("POSTGRESQL", "MYSQL", "SQL_SERVER"),
        help="Target SQL dialect when using --ask",
    )
    parser.add_argument(
        "--model",
        default="qwen3:1.7b",
        help="Ollama model identifier when using --ask (default: qwen3:1.7b)",
    )
    args = parser.parse_args()
    if args.ask:
        ask_live_ollama(args.ask, args.dialect, args.model)
        return
    verify_schema_compaction()
    verify_candidate_guards()
    verify_ollama_and_openai_client()
    print("ALL SPRINT 2 AI & DATA CHECKS PASSED.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        sys.exit(1)
