#!/usr/bin/env python3
"""Sprint 3 Automated Verification Script for AI & Data Deliverables (T-3.1.1, T-3.2.1, T-3.3.2)."""

import json
import os
import sys
from pathlib import Path

import httpx
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "ai-service"))

from app import app  # noqa: E402
from candidate_validation import InvalidAggregation, validate_aggregation  # noqa: E402


def _semantic_context(cardinality: str = "MANY_TO_ONE") -> dict:
    events = {"schema": "analytics", "name": "events"}
    channels = {"schema": "analytics", "name": "channels"}
    return {
        "tables": [
            {
                **events,
                "columns": [
                    {"name": "id", "dataType": "BIGINT"},
                    {"name": "channel_id", "dataType": "BIGINT"},
                    {"name": "amount", "dataType": "DECIMAL"},
                ],
                "primaryKey": ["id"],
            },
            {
                **channels,
                "columns": [
                    {"name": "id", "dataType": "BIGINT"},
                    {"name": "label", "dataType": "STRING"},
                ],
                "primaryKey": ["id"],
            },
        ],
        "metrics": [
            {
                "name": "total_amount",
                "expression": "SUM(amount)",
                "tableRefs": [events],
                "compatibleDimensions": ["channel"],
            }
        ],
        "dimensions": [
            {
                "name": "channel",
                "table": channels,
                "column": "label",
                "dataType": "STRING",
            }
        ],
        "relationships": [
            {
                "from": {"table": events, "column": "channel_id"},
                "to": {"table": channels, "column": "id"},
                "cardinality": cardinality,
            }
        ],
        "examples": [
            {
                "question": "How many events?",
                "sqlDialect": "POSTGRESQL",
                "sql": 'SELECT COUNT(*) AS count FROM "analytics"."events"',
            }
        ],
        "businessRules": ["Amount is measured before tax."],
        "semanticVersion": "1.0.0",
        "schemaVersion": "1.0.0",
        "timezone": "Asia/Ho_Chi_Minh",
        "referenceTime": "2026-11-18T00:00:00Z",
    }


def _auth_headers(token: str, corr_id: str, extra: dict | None = None) -> dict:
    base = {"Authorization": f"Bearer {token}", "X-Correlation-ID": corr_id}
    if extra:
        base.update(extra)
    return base


def verify_semantic_plan_and_business(token: str) -> None:
    os.environ["PYTHON_AI_TOKEN"] = token
    dialects = {
        "POSTGRESQL": ("postgres", "public", 'SELECT COUNT(*) FROM public."Album"'),
        "MYSQL": ("mysql", "public", "SELECT COUNT(*) FROM `public`.`Album`"),
        "SQL_SERVER": ("mssql", "dbo", "SELECT COUNT(*) FROM [dbo].[Album]"),
    }
    with TestClient(app) as client:
        for dialect, (source, schema, sql) in dialects.items():
            corr_plan = f"s3-plan-{dialect.lower()}"
            plan_res = client.post(
                "/internal/v1/semantic-plan",
                json={
                    "correlationId": corr_plan,
                    "sqlDialect": dialect,
                    "sql": sql,
                    "manifest": {
                        "catalog": "nla",
                        "schema": "semantic",
                        "dataSource": source,
                        "models": [
                            {
                                "name": "nla_model_0",
                                "tableReference": {"schema": schema, "table": "Album"},
                                "columns": [
                                    {"name": "AlbumId", "type": "INTEGER"},
                                    {"name": "Title", "type": "STRING"},
                                ],
                                "primaryKey": "AlbumId",
                            }
                        ],
                        "relationships": [],
                    },
                },
                headers=_auth_headers(token, corr_plan),
            )
            assert plan_res.status_code == 200, plan_res.text
            planned = plan_res.json()
            assert planned["plannerVersion"] == "wren-core-py/0.4.0"
            assert planned["sourceTables"][0]["name"] == "Album"
            assert "nla_model_0" not in planned["sql"]
            assert "WITH" not in planned["sql"].upper()

            corr_val = f"s3-bval-{dialect.lower()}"
            val_res = client.post(
                "/internal/v1/business-validate",
                json={
                    "correlationId": corr_val,
                    "warehouseId": "wh-s3",
                    "datasetId": "ds-s3",
                    "sqlDialect": dialect,
                    "semanticContext": _semantic_context(),
                },
                headers=_auth_headers(token, corr_val),
            )
            assert val_res.status_code == 200, val_res.text
            assert val_res.json()["valid"] is True

            corr_bplan = f"s3-bplan-{dialect.lower()}"
            bplan_res = client.post(
                "/internal/v1/business-plan",
                json={
                    "correlationId": corr_bplan,
                    "warehouseId": "wh-s3",
                    "datasetId": "ds-s3",
                    "sqlDialect": dialect,
                    "semanticContext": _semantic_context(),
                    "selection": {
                        "metrics": ["total_amount"],
                        "dimensions": ["channel"],
                        "filters": [{"dimension": "channel", "operator": "EQ", "value": "web"}],
                        "limit": 25,
                    },
                },
                headers=_auth_headers(token, corr_bplan),
            )
            assert bplan_res.status_code == 200, bplan_res.text
            assert bplan_res.json()["model"] == "semantic-compiler"
            assert "SUM" in bplan_res.json()["sql"].upper()

        bad_plan = client.post(
            "/internal/v1/semantic-plan",
            json={
                "correlationId": "s3-plan-bad",
                "sqlDialect": "POSTGRESQL",
                "sql": "SELECT COUNT(*) FROM public.MissingTable",
                "manifest": {
                    "catalog": "nla",
                    "schema": "semantic",
                    "dataSource": "postgres",
                    "models": [
                        {
                            "name": "nla_model_0",
                            "tableReference": {"schema": "public", "table": "Album"},
                            "columns": [{"name": "AlbumId", "type": "INTEGER"}],
                            "primaryKey": "AlbumId",
                        }
                    ],
                    "relationships": [],
                },
            },
            headers=_auth_headers(token, "s3-plan-bad"),
        )
        assert bad_plan.status_code == 422
        assert bad_plan.json()["error"]["code"] == "INSUFFICIENT_CONTEXT"

        fanout_res = client.post(
            "/internal/v1/business-plan",
            json={
                "correlationId": "s3-fanout",
                "warehouseId": "wh-s3",
                "datasetId": "ds-s3",
                "sqlDialect": "POSTGRESQL",
                "semanticContext": _semantic_context(cardinality="ONE_TO_MANY"),
                "selection": {
                    "metrics": ["total_amount"],
                    "dimensions": ["channel"],
                    "filters": [],
                    "limit": 10,
                },
            },
            headers=_auth_headers(token, "s3-fanout"),
        )
        assert fanout_res.status_code == 422
        assert fanout_res.json()["error"]["code"] == "SEMANTIC_MODEL_INVALID"

    print("PASS [T-3.1.1]: Native semantic planner & business compiler verified across 3 dialects.")


def verify_semantic_assist(token: str) -> None:
    os.environ["PYTHON_AI_TOKEN"] = token
    os.environ["LLM_BASE_URL"] = "http://localhost:11434/v1"
    os.environ["LLM_MODEL"] = "qwen3:4b"
    os.environ["LLM_API_KEY"] = "secret-test-key"
    os.environ["LLM_STRUCTURED_OUTPUT"] = "true"

    valid_reply = {
        "metrics": [
            {
                "name": "event_count",
                "description": "Count physical events",
                "expression": "COUNT(*)",
                "tableRefs": [{"schema": "analytics", "name": "events"}],
                "compatibleDimensions": ["channel"],
                "unit": "",
                "synonyms": [],
            }
        ],
        "dimensions": [],
        "businessRules": ["Review drafted KPI definitions before activation."],
        "warnings": [],
    }
    hallucinated_reply = {
        "metrics": [
            {
                "name": "hallucinated_metric",
                "description": "Should fail closed on unknown physical column",
                "expression": "SUM(nonexistent_col)",
                "tableRefs": [{"schema": "analytics", "name": "events"}],
                "compatibleDimensions": ["channel"],
                "unit": "",
                "synonyms": [],
            }
        ],
        "dimensions": [],
        "businessRules": ["Review drafted KPI definitions before activation."],
        "warnings": [],
    }
    captured_requests = []
    next_reply = {"payload": valid_reply}

    def handler(request: httpx.Request) -> httpx.Response:
        captured_requests.append(json.loads(request.content))
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": json.dumps(next_reply["payload"])}}]},
        )

    app.state.provider_transport = httpx.MockTransport(handler)
    try:
        with TestClient(app) as client:
            for dialect in ("POSTGRESQL", "MYSQL", "SQL_SERVER"):
                corr = f"s3-assist-{dialect.lower()}"
                res = client.post(
                    "/internal/v1/semantic-assist",
                    json={
                        "correlationId": corr,
                        "warehouseId": "wh-s3",
                        "datasetId": "ds-s3",
                        "sqlDialect": dialect,
                        "semanticContext": _semantic_context(),
                        "instruction": "Propose an event count metric while keeping existing metrics.",
                    },
                    headers=_auth_headers(token, corr),
                )
                assert res.status_code == 200, res.text
                body = res.json()
                assert [m["name"] for m in body["metrics"]] == ["event_count"]
                assert body["warnings"]
                assert "secret-test-key" not in json.dumps(captured_requests[-1])

            next_reply["payload"] = hallucinated_reply
            bad_res = client.post(
                "/internal/v1/semantic-assist",
                json={
                    "correlationId": "s3-assist-invalid",
                    "warehouseId": "wh-s3",
                    "datasetId": "ds-s3",
                    "sqlDialect": "POSTGRESQL",
                    "semanticContext": _semantic_context(),
                    "instruction": "Propose a metric over nonexistent column.",
                },
                headers=_auth_headers(token, "s3-assist-invalid"),
            )
            assert bad_res.status_code == 502, bad_res.text
            assert bad_res.json()["error"]["code"] == "INVALID_MODEL_RESPONSE"
    finally:
        if hasattr(app.state, "provider_transport"):
            del app.state.provider_transport

    print("PASS [T-3.2.1]: AI semantic assist grounding & fail-closed validation verified.")


def verify_aggregation_and_self_correction(token: str) -> None:
    os.environ["PYTHON_AI_TOKEN"] = token
    os.environ["LLM_BASE_URL"] = "http://localhost:11434/v1"
    os.environ["LLM_MODEL"] = "qwen3:4b"
    os.environ["LLM_STRUCTURED_OUTPUT"] = "true"

    ctx = {
        "tables": [
            {
                "schema": "analytics",
                "name": "observations",
                "columns": [
                    {"name": "station", "dataType": "STRING"},
                    {"name": "temperature", "dataType": "DECIMAL"},
                ],
            }
        ],
        "metrics": [],
        "dimensions": [],
        "relationships": [],
        "examples": [],
        "businessRules": [],
        "semanticVersion": "1.0.0",
        "schemaVersion": "1.0.0",
        "timezone": "UTC",
        "referenceTime": "2026-11-18T00:00:00Z",
    }

    try:
        validate_aggregation(
            "SELECT station, AVG(temperature) FROM analytics.observations",
            "POSTGRESQL",
            ctx,
        )
        raise AssertionError("Expected InvalidAggregation for ungrouped column")
    except InvalidAggregation:
        pass

    turns = []

    def repair_handler(request: httpx.Request) -> httpx.Response:
        turns.append(json.loads(request.content))
        chart_recommendation = {
            "type": "bar",
            "x": "station",
            "title": "Avg Temperature",
            "series": [{"column": "avg_temp", "label": "Average"}],
        }
        if len(turns) == 1:
            candidate = {
                "sql": "SELECT station, AVG(temperature) AS avg_temp FROM analytics.observations",
                "explanation": "Average temperature per station",
                "assumptions": [],
                "selectedMetrics": [],
                "selectedDimensions": [],
                "confidence": 0.85,
                "model": "qwen3:4b",
                "chartRecommendation": chart_recommendation,
            }
        else:
            candidate = {
                "sql": (
                    "SELECT station, AVG(temperature) AS avg_temp "
                    "FROM analytics.observations GROUP BY station"
                ),
                "explanation": "Average temperature grouped by station",
                "assumptions": [],
                "selectedMetrics": [],
                "selectedDimensions": [],
                "confidence": 0.91,
                "model": "qwen3:4b",
                "chartRecommendation": chart_recommendation,
            }
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": json.dumps(candidate)}}]},
        )

    app.state.provider_transport = httpx.MockTransport(repair_handler)
    try:
        with TestClient(app) as client:
            res = client.post(
                "/internal/v1/text-to-sql",
                json={
                    "correlationId": "s3-repair",
                    "warehouseId": "wh-s3",
                    "datasetId": "ds-s3",
                    "sqlDialect": "POSTGRESQL",
                    "question": "Average temperature per station",
                    "semanticContext": ctx,
                },
                headers=_auth_headers(token, "s3-repair", {"X-Execution-Mode": "API"}),
            )
            assert res.status_code == 200, res.text
            assert len(turns) == 2
            assert "GROUP BY" in res.json()["sql"].upper()
    finally:
        if hasattr(app.state, "provider_transport"):
            del app.state.provider_transport

    print("PASS [T-3.3.2]: Aggregate validation & bounded 1-round self-correction verified.")


def main() -> None:
    token = "sprint3-verify-service-secret-token-32chars"
    verify_semantic_plan_and_business(token)
    verify_semantic_assist(token)
    verify_aggregation_and_self_correction(token)
    print("ALL SPRINT 3 AI & DATA CHECKS PASSED.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        sys.exit(1)
