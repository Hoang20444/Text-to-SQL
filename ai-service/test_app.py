"""Sprint 1 & Sprint 2 test suite for private AI service authentication, compact schema selection,
candidate SQL post-validation across 3 dialects, and async Ollama/OpenAI-compatible generation.
"""

import json
import os
import re
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from app import (
    Candidate,
    Chart,
    Controlled,
    DeterministicModelClient,
    Input,
    SemanticContextSelector,
    app,
    normalize,
    provider_payload,
    serialize_provider_payload,
    sql_code_tokens,
    validate,
)
from context_index import cached_snapshot_count, clear_schema_index_cache, get_schema_index

ROOT = Path(__file__).resolve().parents[1]
TOKEN = "test-service-secret-token-with-minimum-32-characters"


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("PYTHON_AI_TOKEN", TOKEN)
    clear_schema_index_cache()
    previous_transport = getattr(app.state, "provider_transport", None)
    with TestClient(app) as test_client:
        yield test_client
    if previous_transport is None:
        if hasattr(app.state, "provider_transport"):
            delattr(app.state, "provider_transport")
    else:
        app.state.provider_transport = previous_transport


def payload(engine="POSTGRESQL", example_index=0):
    suffix = {"POSTGRESQL": "postgresql", "MYSQL": "mysql", "SQL_SERVER": "mssql"}[engine]
    ctx = json.loads((ROOT / f"data/chinook/semantic-{suffix}.json").read_text(encoding="utf-8"))
    tables = {}
    for metric in ctx["metrics"]:
        for table_ref in metric["tableRefs"]:
            key = (table_ref["schema"], table_ref["name"])
            tables.setdefault(key, set())
    for dimension in ctx["dimensions"]:
        key = (dimension["table"]["schema"], dimension["table"]["name"])
        tables.setdefault(key, set()).add(dimension["column"])
    identifier = r'(?:"[^"]+"|`[^`]+`|\[[^\]]+\]|[A-Za-z_][A-Za-z0-9_$#]*)'
    table_pattern = re.compile(
        rf"\b(?:FROM|JOIN)\s+(?:({identifier})\s*\.\s*)?({identifier})",
        re.IGNORECASE,
    )

    def unquote(identifier_value: str) -> str:
        if identifier_value[0] in {'"', "`", "["}:
            return identifier_value[1:-1]
        return identifier_value

    default_schema = "dbo" if engine == "SQL_SERVER" else "public"
    for example in ctx["examples"]:
        for match in table_pattern.finditer(example["sql"]):
            schema = unquote(match.group(1)) if match.group(1) else default_schema
            name = unquote(match.group(2))
            tables.setdefault((schema, name), set())
    for schema, name in tables:
        tables[(schema, name)].add("track_id")
    ctx["tables"] = [
        {
            "schema": schema,
            "name": name,
            "columns": [{"name": column, "dataType": "STRING"} for column in sorted(columns)],
        }
        for (schema, name), columns in tables.items()
    ]
    return {
        "correlationId": "test-corr",
        "warehouseId": f"wh-chinook-{suffix}",
        "datasetId": f"ds-chinook-{suffix}",
        "sqlDialect": engine,
        "question": ctx["examples"][example_index]["question"],
        "semanticContext": ctx,
    }


def headers(mode="DETERMINISTIC"):
    return {
        "Authorization": "Bearer " + TOKEN,
        "X-Correlation-ID": "test-corr",
        "X-Execution-Mode": mode,
    }


def provider_response(
    sql='SELECT COUNT(*) AS track_count FROM "public"."track"',
    selected_metrics=None,
):
    content = {
        "sql": sql,
        "explanation": "Counts tracks in the selected catalog.",
        "assumptions": [],
        "selectedMetrics": selected_metrics or [],
        "selectedDimensions": [],
        "chartRecommendation": {
            "type": "number",
            "title": "Track count",
            "series": [{"column": "track_count", "label": "Tracks"}],
        },
        "confidence": 0.95,
        "model": "provider-must-not-override-configured-name",
    }
    return {"choices": [{"message": {"content": json.dumps(content)}}]}


def enable_provider(monkeypatch, response_body=None, status=200):
    monkeypatch.setenv("LLM_BASE_URL", "https://llm.example.test/v1")
    monkeypatch.setenv("LLM_API_KEY", "provider-test-secret")
    monkeypatch.setenv("LLM_MODEL", "configured-test-model")

    def handler(request):
        assert request.url.path == "/v1/chat/completions"
        assert request.headers["authorization"] == "Bearer provider-test-secret"
        sent = json.loads(request.content)
        assert sent["model"] == "configured-test-model"
        assert sent["temperature"] == 0
        return httpx.Response(status, json=response_body or provider_response())

    app.state.provider_transport = httpx.MockTransport(handler)


@pytest.mark.parametrize("engine", ["POSTGRESQL", "MYSQL", "SQL_SERVER"])
def test_deterministic_uses_declared_dialect(client, engine):
    body = payload(engine)
    response = client.post("/internal/v1/text-to-sql", json=body, headers=headers())
    assert response.status_code == 200, response.text
    result = response.json()
    assert result["sql"] == body["semanticContext"]["examples"][0]["sql"]
    assert result["model"] == "deterministic-semantic-examples"
    assert result["confidence"] == 1.0


@pytest.mark.parametrize("engine", ["POSTGRESQL", "MYSQL", "SQL_SERVER"])
def test_all_chinook_examples_execute_in_deterministic_mode(client, engine):
    sample = payload(engine)
    total = len(sample["semanticContext"]["examples"])
    assert total >= 3
    for idx in range(total):
        body = payload(engine, example_index=idx)
        response = client.post("/internal/v1/text-to-sql", json=body, headers=headers())
        assert response.status_code == 200, f"{engine}[{idx}] failed: {response.text}"
        assert response.json()["sql"] == body["semanticContext"]["examples"][idx]["sql"]


def test_no_auth_rejected_401(client):
    assert client.post("/internal/v1/text-to-sql", json=payload()).status_code == 401


def test_short_service_token_rejected_401(monkeypatch):
    monkeypatch.setenv("PYTHON_AI_TOKEN", "too-short")
    with TestClient(app) as short_client:
        response = short_client.post("/internal/v1/text-to-sql", json=payload(), headers=headers())
        assert response.status_code == 401


def test_correlation_mismatch_rejected_400(client):
    response = client.post(
        "/internal/v1/text-to-sql",
        json={**payload(), "correlationId": "different-corr"},
        headers=headers(),
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_REQUEST"


def test_unknown_secret_request_fields_rejected_422(client):
    response = client.post(
        "/internal/v1/text-to-sql",
        json={**payload(), "password": "secret-db-password"},
        headers=headers(),
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "INVALID_REQUEST"


def test_unknown_nested_context_fields_are_rejected_422(client):
    body = payload()
    body["semanticContext"]["tables"][0]["connectionString"] = "jdbc:postgresql://secret"
    response = client.post("/internal/v1/text-to-sql", json=body, headers=headers())
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "INVALID_REQUEST"


def test_unsupported_question_not_fabricated(client):
    response = client.post(
        "/internal/v1/text-to-sql",
        json={**payload(), "question": "Delete all records from invoice"},
        headers=headers(),
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "UNSUPPORTED_QUESTION"


def test_empty_tables_returns_insufficient_context(client):
    body = payload()
    body["semanticContext"]["tables"] = []
    response = client.post("/internal/v1/text-to-sql", json=body, headers=headers())
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "INSUFFICIENT_CONTEXT"


def test_api_mode_without_provider_never_falls_back(client, monkeypatch):
    monkeypatch.delenv("LLM_API_KEY", raising=False)
    monkeypatch.delenv("LLM_BASE_URL", raising=False)
    response = client.post("/internal/v1/text-to-sql", json=payload(), headers=headers("API"))
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "PROVIDER_UNAVAILABLE"


def test_qwen3_ollama_prompt_adds_no_think_and_disables_reasoning_by_default(monkeypatch):
    body = payload()
    body["question"] = "Tổng YearlyIncome theo DateFirstPurchase?"
    request = Input.model_validate(body)
    monkeypatch.setenv("LLM_PROVIDER_KIND", "OLLAMA")
    monkeypatch.setenv("LLM_BASE_URL", "http://127.0.0.1:11434/v1")
    monkeypatch.setenv("LLM_MODEL", "qwen3:4b")
    monkeypatch.delenv("LLM_REASONING_EFFORT", raising=False)

    generated = provider_payload(
        request,
        request.semanticContext.model_dump(by_alias=True),
        "qwen3:4b",
        provider_kind="OLLAMA",
    )

    assert generated["messages"][0]["content"].endswith("/no_think")
    assert generated["reasoning_effort"] == "none"
    user_content = json.loads(generated["messages"][1]["content"])
    assert user_content["question"] == body["question"]


def test_provider_api_returns_strict_candidate(client, monkeypatch):
    enable_provider(monkeypatch)
    response = client.post("/internal/v1/text-to-sql", json=payload(), headers=headers("API"))
    assert response.status_code == 200, response.text
    assert response.json()["model"] == "configured-test-model"


def test_provider_boundary_compacts_large_schema_without_losing_join_path(client, monkeypatch):
    body = payload()
    context = body["semanticContext"]
    body["question"] = "Tổng doanh số theo tên bản nhạc?"
    invoice = next(table for table in context["tables"] if table["name"] == "invoice")
    track = next(table for table in context["tables"] if table["name"] == "track")
    invoice_line = {
        "schema": "public",
        "name": "invoice_line",
        "logicalName": "Invoice line",
        "columns": [
            {"name": "invoice_id", "dataType": "INTEGER"},
            {"name": "track_id", "dataType": "INTEGER"},
        ],
    }
    existing_invoice_line = next(
        (table for table in context["tables"] if table["name"] == "invoice_line"),
        None,
    )
    if existing_invoice_line is None:
        context["tables"].append(invoice_line)
    else:
        existing_columns = {column["name"] for column in existing_invoice_line["columns"]}
        existing_invoice_line["columns"].extend(
            column for column in invoice_line["columns"] if column["name"] not in existing_columns
        )
    next(metric for metric in context["metrics"] if metric["name"] == "total_sales")[
        "compatibleDimensions"
    ].append("track_name")
    context["relationships"] = [
        {
            "from": {"table": {"schema": "public", "name": "invoice"}, "column": "track_id"},
            "to": {"table": {"schema": "public", "name": "invoice_line"}, "column": "invoice_id"},
            "cardinality": "ONE_TO_MANY",
        },
        {
            "from": {"table": {"schema": "public", "name": "invoice_line"}, "column": "track_id"},
            "to": {"table": {"schema": "public", "name": "track"}, "column": "track_id"},
            "cardinality": "MANY_TO_ONE",
        },
    ]
    for i in range(40):
        context["tables"].append(
            {
                "schema": "unrelated",
                "name": f"archive_{i}",
                "description": "Unrelated archival shipment records",
                "columns": [
                    {"name": f"col_{j}", "dataType": "VARCHAR", "description": "Unrelated"}
                    for j in range(25)
                ],
            }
        )

    monkeypatch.setenv("LLM_MODEL", "configured-test-model")
    monkeypatch.setenv("LLM_BASE_URL", "https://llm.example.test/v1")
    monkeypatch.setenv("LLM_API_KEY", "provider-test-secret")
    request = Input.model_validate(body)
    complete_body = serialize_provider_payload(request, context, "configured-test-model")

    monkeypatch.setenv("LLM_REQUEST_MAX_BYTES", "25000")
    selected = SemanticContextSelector().select(request)
    selected_body = serialize_provider_payload(request, selected, "configured-test-model")
    assert len(selected_body) < len(complete_body) * 0.15
    assert {table["name"] for table in selected["tables"]} == {"invoice", "track", "invoice_line"}
    assert len(selected["relationships"]) == 2
    assert len(invoice["columns"]) >= 1 and len(track["columns"]) >= 1


def test_context_cache_reuses_structure_but_sends_fresh_reference_time(client, monkeypatch):
    clear_schema_index_cache()
    body = payload()
    body["question"] = "How many tracks?"
    monkeypatch.setenv("LLM_MODEL", "configured-test-model")
    monkeypatch.setenv("LLM_BASE_URL", "https://llm.example.test/v1")
    monkeypatch.setenv("LLM_API_KEY", "provider-test-secret")
    observed_times = []

    def handler(provider_request):
        sent = json.loads(provider_request.content)
        user_msg = json.loads(sent["messages"][1]["content"])
        observed_times.append(user_msg["semanticContext"]["referenceTime"])
        return httpx.Response(200, json=provider_response())

    app.state.provider_transport = httpx.MockTransport(handler)
    for reference_time in ("2026-10-20T12:00:00Z", "2026-10-20T12:01:00Z"):
        body["semanticContext"]["referenceTime"] = reference_time
        response = client.post("/internal/v1/text-to-sql", json=body, headers=headers("API"))
        assert response.status_code == 200, response.text

    assert cached_snapshot_count() == 1
    assert observed_times == ["2026-10-20T12:00:00Z", "2026-10-20T12:01:00Z"]


@pytest.mark.parametrize(
    ("sql", "code"),
    [
        ("SELECT * FROM track; DROP TABLE track", "INVALID_MODEL_RESPONSE"),
        ("SELECT pg_sleep(10)", "INVALID_MODEL_RESPONSE"),
        ("SELECT * FROM track -- comment", "INVALID_MODEL_RESPONSE"),
        ("SELECT * FROM track # hidden comment", "INVALID_MODEL_RESPONSE"),
        ("SELECT * FROM OPENROWSET('provider', 'source')", "INVALID_MODEL_RESPONSE"),
        ("SELECT * FROM `track`", "SQL_DIALECT_ERROR"),
        ("SELECT TOP (5) * FROM track", "SQL_DIALECT_ERROR"),
    ],
)
def test_provider_candidate_checks_are_enforced(client, monkeypatch, sql, code):
    enable_provider(monkeypatch, provider_response(sql))
    response = client.post("/internal/v1/text-to-sql", json=payload(), headers=headers("API"))
    assert response.status_code == 502
    assert response.json()["error"]["code"] == code


def test_provider_timeout_and_error_sanitization(client, monkeypatch):
    monkeypatch.setenv("LLM_BASE_URL", "https://llm.example.test/v1")
    monkeypatch.setenv("LLM_API_KEY", "provider-test-secret")
    monkeypatch.setenv("LLM_MODEL", "configured-test-model")

    def timeout(_request):
        raise httpx.ReadTimeout("private provider detail")

    app.state.provider_transport = httpx.MockTransport(timeout)
    response = client.post("/internal/v1/text-to-sql", json=payload(), headers=headers("API"))
    assert response.status_code == 504
    assert response.json()["error"]["code"] == "PROVIDER_TIMEOUT"
    assert "private provider detail" not in response.text


def test_provider_prompt_excludes_warehouse_ids_and_credentials(client, monkeypatch):
    monkeypatch.setenv("LLM_BASE_URL", "https://llm.example.test/v1")
    monkeypatch.setenv("LLM_API_KEY", "provider-test-secret")
    monkeypatch.setenv("LLM_MODEL", "configured-test-model")
    captured = {}

    def handler(request):
        captured.update(json.loads(request.content))
        return httpx.Response(200, json=provider_response())

    app.state.provider_transport = httpx.MockTransport(handler)
    response = client.post("/internal/v1/text-to-sql", json=payload(), headers=headers("API"))
    assert response.status_code == 200, response.text
    prompt = json.dumps(captured)
    for private_value in (
        "warehouseId",
        "datasetId",
        "credentialReference",
        "connectionString",
        "test-service-secret-token",
    ):
        assert private_value not in prompt


def test_sql_safety_guards_reject_mutations_comments_and_wrong_dialect():
    body = payload("SQL_SERVER")
    ctx = body["semanticContext"]
    with pytest.raises(Controlled):
        sql_code_tokens("SELECT 1; DROP TABLE Album;")
    with pytest.raises(Controlled):
        sql_code_tokens("SELECT 1 -- inline comment")

    bad_limit_mssql = Candidate(
        sql="SELECT [AlbumId] FROM [dbo].[Album] LIMIT 5",
        explanation="test",
        assumptions=[],
        selectedMetrics=[],
        selectedDimensions=[],
        chartRecommendation=Chart(type="table", title="Test", series=[]),
        confidence=0.9,
        model="test",
    )
    with pytest.raises(Controlled) as exc_info:
        validate(bad_limit_mssql, ctx, "SQL_SERVER")
    assert exc_info.value.code == "SQL_DIALECT_ERROR"


def test_schema_index_normalizes_vietnamese_terms():
    body = payload("POSTGRESQL")
    idx = get_schema_index(body["semanticContext"])
    assert len(idx["tableKeys"]) >= 1
