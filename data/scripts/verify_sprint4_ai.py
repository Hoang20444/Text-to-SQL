#!/usr/bin/env python3
"""Sprint 4 Automated Verification Script for AI & Data Deliverables (T-4.1.1, T-4.2.1, T-4.3.3)."""

import hashlib
import json
import os
import sys
import tempfile
from pathlib import Path

import httpx
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "ai-service"))

import knowledge  # noqa: E402
from app import Input, SemanticContextSelector, app  # noqa: E402
from check_memory_runtime import check as check_lancedb_runtime  # noqa: E402


class _FixedEmbeddings:
    def compute_source_embeddings(self, texts: list[str]) -> list[list[float]]:
        return [
            [(value + 1) / 256 for value in hashlib.sha256(text.encode()).digest()]
            for text in texts
        ]

    def compute_query_embeddings(self, query: str | list[str]) -> list[list[float]]:
        items = [query] if isinstance(query, str) else query
        return self.compute_source_embeddings(items)


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
                c("country", "Country", "STRING"),
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


def verify_knowledge_vector_memory(token: str) -> None:
    check_lancedb_runtime()
    original_embeddings = knowledge.LocalEmbeddings
    original_root = os.environ.get("KNOWLEDGE_ROOT")
    original_enabled = os.environ.get("KNOWLEDGE_ENABLED")

    with tempfile.TemporaryDirectory(prefix="nla-s4-verify-") as temp_dir:
        os.environ["PYTHON_AI_TOKEN"] = token
        os.environ["KNOWLEDGE_ROOT"] = temp_dir
        os.environ["KNOWLEDGE_ENABLED"] = "true"
        os.environ["LLM_BASE_URL"] = "http://localhost:11434/v1"
        os.environ["LLM_MODEL"] = "qwen3:4b"
        os.environ["LLM_API_KEY"] = "secret-verify-key"
        knowledge.LocalEmbeddings = _FixedEmbeddings

        context = _chinook_context("POSTGRESQL")
        base_body = {
            "correlationId": "s4-knowledge",
            "warehouseId": "wh-s4",
            "datasetId": "ds-s4",
            "sqlDialect": "POSTGRESQL",
            "semanticContext": context,
        }
        headers = {"Authorization": f"Bearer {token}", "X-Correlation-ID": "s4-knowledge"}

        try:
            with TestClient(app) as client:
                st = client.post("/internal/v1/knowledge/status", json=base_body, headers=headers)
                assert st.status_code == 200 and st.json()["status"] == "EMPTY", st.text

                idx = client.post("/internal/v1/knowledge/index", json=base_body, headers=headers)
                assert idx.status_code == 200 and idx.json()["status"] == "READY", idx.text
                assert idx.json()["schemaItems"] > 0

                srch = client.post(
                    "/internal/v1/knowledge/search",
                    json={**base_body, "question": "doanh thu theo quoc gia", "limit": 5},
                    headers=headers,
                )
                assert srch.status_code == 200 and srch.json()["strategy"] == "vector", srch.text
                assert srch.json()["hits"]

                rem = client.post(
                    "/internal/v1/knowledge/remember",
                    json={
                        **base_body,
                        "example": {
                            "question": "Bao nhieu hoa don?",
                            "sql": 'SELECT COUNT(*) AS invoice_count FROM "public"."invoice"',
                        },
                    },
                    headers=headers,
                )
                assert rem.status_code == 200, rem.text
                entry_id = next(
                    item["entryId"]
                    for item in rem.json()["items"]
                    if item["source"] == "approved-query"
                )

                fgt = client.post(
                    "/internal/v1/knowledge/forget",
                    json={**base_body, "entryId": entry_id},
                    headers=headers,
                )
                assert fgt.status_code == 200, fgt.text

                rst = client.post("/internal/v1/knowledge/reset", json=base_body, headers=headers)
                assert rst.status_code == 200 and rst.json()["status"] == "EMPTY", rst.text
        finally:
            knowledge.LocalEmbeddings = original_embeddings
            if original_root is None:
                os.environ.pop("KNOWLEDGE_ROOT", None)
            else:
                os.environ["KNOWLEDGE_ROOT"] = original_root
            if original_enabled is None:
                os.environ.pop("KNOWLEDGE_ENABLED", None)
            else:
                os.environ["KNOWLEDGE_ENABLED"] = original_enabled

    print("PASS [T-4.1.1]: LanceDB dataset knowledge index/search/remember/forget/reset verified.")


def verify_unaccented_bridges_and_followup() -> None:
    for engine in ("POSTGRESQL", "MYSQL", "SQL_SERVER"):
        context = _chinook_context(engine)
        followup_req = Input.model_validate(
            {
                "correlationId": f"s4-bridge-{engine.lower()}",
                "warehouseId": "wh-chinook",
                "datasetId": "ds-chinook",
                "sqlDialect": engine,
                "question": (
                    "Previous question context: doanh thu theo nghe si?\n"
                    "Follow-up question: chi lay top 5"
                ),
                "semanticContext": context,
            }
        )
        selected = SemanticContextSelector().select(followup_req)
        names = {t["name"].casefold() for t in selected["tables"]}
        expected_line = "invoice_line" if engine == "POSTGRESQL" else "invoiceline"
        assert {"artist", "album", "track", expected_line} <= names, (engine, names)

    print("PASS [T-4.2.1]: Unaccented Vietnamese FK bridging & follow-up context verified.")


def verify_chart_alignment(token: str) -> None:
    os.environ["PYTHON_AI_TOKEN"] = token
    os.environ["KNOWLEDGE_ENABLED"] = "false"
    os.environ["LLM_BASE_URL"] = "http://localhost:11434/v1"
    os.environ["LLM_MODEL"] = "qwen3:4b"
    os.environ["LLM_API_KEY"] = "secret-verify-key"

    def handler(request: httpx.Request) -> httpx.Response:
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
                                        'SUM(il."unit_price" * il."quantity") AS revenue '
                                        'FROM "public"."artist" a '
                                        'JOIN "public"."album" al ON al."artist_id" = a."artist_id" '
                                        'JOIN "public"."track" t ON t."album_id" = al."album_id" '
                                        'JOIN "public"."invoice_line" il ON il."track_id" = t."track_id" '
                                        'GROUP BY a."artist_id", a."name" '
                                        "ORDER BY revenue DESC LIMIT 5"
                                    ),
                                    "explanation": "Top 5 nghệ sĩ theo doanh thu.",
                                    "assumptions": [],
                                    "selectedMetrics": [],
                                    "selectedDimensions": [],
                                    "chartRecommendation": {
                                        "type": "bar",
                                        "title": "Top 5 nghệ sĩ theo doanh thu",
                                        "x": "artist_name",
                                        "series": [{"column": "revenue", "label": "Doanh thu"}],
                                    },
                                    "confidence": 0.93,
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
                    "correlationId": "s4-chart",
                    "warehouseId": "wh-chinook",
                    "datasetId": "ds-chinook",
                    "sqlDialect": "POSTGRESQL",
                    "question": "Top 5 nghe si co doanh thu cao nhat?",
                    "semanticContext": _chinook_context("POSTGRESQL"),
                },
                headers={
                    "Authorization": f"Bearer {token}",
                    "X-Correlation-ID": "s4-chart",
                    "X-Execution-Mode": "API",
                },
            )
            assert res.status_code == 200, res.text
            chart = res.json()["chartRecommendation"]
            assert chart["type"] == "bar" and chart["x"] == "artist_name"
            assert chart["series"][0]["column"] == "revenue"
    finally:
        if hasattr(app.state, "provider_transport"):
            del app.state.provider_transport

    print("PASS [T-4.3.3]: Chart recommendation alignment & alias checks verified.")


def main() -> None:
    token = "sprint4-verify-service-secret-token-32chars"
    verify_knowledge_vector_memory(token)
    verify_unaccented_bridges_and_followup()
    verify_chart_alignment(token)
    print("ALL SPRINT 4 AI & DATA CHECKS PASSED.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        sys.exit(1)
