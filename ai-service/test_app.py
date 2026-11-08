"""Sprint 1 test suite for private AI service authentication, secret-free input validation,
SQL safety guards, and deterministic 3-engine generation (PostgreSQL, MySQL, SQL Server).
"""

import json
import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import Candidate, Chart, Controlled, app, sql_code_tokens, validate
from context_index import clear_schema_index_cache, get_schema_index

ROOT = Path(__file__).resolve().parents[1]
TOKEN = "test-service-secret-token-with-minimum-32-characters"


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("PYTHON_AI_TOKEN", TOKEN)
    clear_schema_index_cache()
    with TestClient(app) as test_client:
        yield test_client


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
    response = client.post("/internal/v1/text-to-sql", json=payload(), headers=headers("API"))
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "PROVIDER_UNAVAILABLE"


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
