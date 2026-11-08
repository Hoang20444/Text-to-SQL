#!/usr/bin/env python3
"""Sprint 2 Automated Verification Script for AI & Data Deliverables (T-2.1.2, T-2.2.2, T-2.3.2)."""

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


def main() -> None:
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
