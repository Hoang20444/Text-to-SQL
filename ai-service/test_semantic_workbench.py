"""Cross-dialect semantic compilation and suggestion boundaries."""

import json

import httpx
import pytest
from fastapi.testclient import TestClient

from app import app
from semantic_compiler import BusinessCompiler, SemanticCompileError

TOKEN = "semantic-workbench-tests-service-token-32-characters"
DIALECTS = ["POSTGRESQL", "MYSQL", "SQL_SERVER"]


def semantic_context():
    event_table = {"schema": "analytics", "name": "events"}
    channel_table = {"schema": "analytics", "name": "channels"}
    return {
        "tables": [
            {
                **event_table,
                "columns": [
                    {"name": "id", "dataType": "BIGINT"},
                    {"name": "channel_id", "dataType": "BIGINT"},
                    {"name": "amount", "dataType": "DECIMAL"},
                ],
            },
            {
                **channel_table,
                "columns": [
                    {"name": "id", "dataType": "BIGINT"},
                    {"name": "label", "dataType": "STRING"},
                ],
            },
        ],
        "metrics": [
            {
                "name": "total_value",
                "description": "Approved amount total.",
                "expression": "SUM(analytics.events.amount)",
                "unit": "",
                "tableRefs": [event_table],
                "compatibleDimensions": ["channel"],
                "synonyms": ["total", "tổng"],
            }
        ],
        "dimensions": [
            {
                "name": "channel",
                "description": "Event channel.",
                "table": channel_table,
                "column": "label",
                "dataType": "STRING",
                "synonyms": ["channel", "kênh"],
            }
        ],
        "relationships": [
            {
                "from": {"table": event_table, "column": "channel_id"},
                "to": {"table": channel_table, "column": "id"},
                "cardinality": "MANY_TO_ONE",
            }
        ],
        "examples": [],
        "businessRules": [],
        "semanticVersion": "1.0.0",
        "schemaVersion": "schema-a",
        "timezone": "UTC",
        "referenceTime": "2026-11-19T00:00:00Z",
    }


def request_body(dialect="POSTGRESQL", instruction="Suggest approved measures and dimensions."):
    return {
        "correlationId": "semantic-assist",
        "warehouseId": "warehouse-authorized-by-java",
        "datasetId": "dataset-authorized-by-java",
        "sqlDialect": dialect,
        "semanticContext": semantic_context(),
        "instruction": instruction,
    }


def private_headers():
    return {
        "Authorization": "Bearer " + TOKEN,
        "X-Correlation-ID": "semantic-assist",
    }


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("PYTHON_AI_TOKEN", TOKEN)
    monkeypatch.setenv("LLM_BASE_URL", "http://localhost:11434/v1")
    monkeypatch.setenv("LLM_MODEL", "local-semantic-model")
    monkeypatch.setenv("LLM_API_KEY", "private-provider-key-never-echo")
    monkeypatch.setenv("LLM_STRUCTURED_OUTPUT", "true")
    with TestClient(app) as test_client:
        yield test_client
    if hasattr(app.state, "provider_transport"):
        del app.state.provider_transport


@pytest.mark.parametrize("dialect", DIALECTS)
def test_business_compiler_uses_authorized_grain_and_portable_dialect(dialect):
    result = BusinessCompiler(semantic_context(), dialect).compile(
        {
            "metrics": ["total_value"],
            "dimensions": ["channel"],
            "filters": [{"dimension": "channel", "operator": "CONTAINS", "value": "rock"}],
            "limit": 25,
        }
    )
    assert "SUM" in result["sql"].upper()
    assert "JOIN" in result["sql"].upper()
    assert result["selectedMetrics"] == ["total_value"]
    assert result["selectedDimensions"] == ["channel"]
    assert result["model"] == "semantic-compiler"
    assert result["chartRecommendation"]["x"] == "channel"
    assert "25" in result["sql"]


@pytest.mark.parametrize("cardinality", ["ONE_TO_MANY", "MANY_TO_MANY"])
def test_compiler_rejects_join_that_can_multiply_measure_rows(cardinality):
    context = semantic_context()
    context["relationships"][0]["cardinality"] = cardinality
    with pytest.raises(SemanticCompileError):
        BusinessCompiler(context, "POSTGRESQL").compile(
            {"metrics": ["total_value"], "dimensions": ["channel"]}
        )


@pytest.mark.parametrize(
    "expression",
    [
        "amount",
        "SUM(unknown_column)",
        "SUM(amount); DELETE FROM analytics.events",
        "SUM(amount) FROM secret.table",
        "SUM(SUM(amount))",
        "SUM(amount) OVER ()",
        "pg_sleep(1)",
    ],
)
def test_compiler_rejects_unapproved_or_nonaggregate_metric_expressions(expression):
    context = semantic_context()
    context["metrics"][0]["expression"] = expression
    with pytest.raises(SemanticCompileError):
        BusinessCompiler(context, "POSTGRESQL")


@pytest.mark.parametrize(
    "filter_value",
    [
        {"dimension": "channel", "operator": "CONTAINS", "value": 10},
        {"dimension": "channel", "operator": "EQ", "value": {"unsafe": "value"}},
        {"dimension": "channel", "operator": "IS_NULL", "value": False},
    ],
)
def test_compiler_rejects_filter_values_that_do_not_match_dimension_type(filter_value):
    context = semantic_context()
    with pytest.raises(SemanticCompileError):
        BusinessCompiler(context, "POSTGRESQL").compile(
            {"metrics": ["total_value"], "dimensions": ["channel"], "filters": [filter_value]}
        )


def test_private_business_plan_validates_auth_and_contract(client):
    body = {
        "correlationId": "semantic-assist",
        "warehouseId": "warehouse-id",
        "datasetId": "dataset-id",
        "sqlDialect": "POSTGRESQL",
        "semanticContext": semantic_context(),
        "selection": {"metrics": ["total_value"], "dimensions": ["channel"], "limit": 5},
    }
    rejected = client.post("/internal/v1/business-plan", json=body)
    assert rejected.status_code == 401
    response = client.post(
        "/internal/v1/business-plan",
        json=body,
        headers=private_headers(),
    )
    assert response.status_code == 200, response.text
    assert response.json()["model"] == "semantic-compiler"
    assert "JOIN" in response.json()["sql"].upper()


def test_private_validation_returns_counts_without_mutation(client):
    body = {
        "correlationId": "semantic-assist",
        "warehouseId": "warehouse-id",
        "datasetId": "dataset-id",
        "sqlDialect": "MYSQL",
        "semanticContext": semantic_context(),
    }
    response = client.post(
        "/internal/v1/business-validate",
        json=body,
        headers=private_headers(),
    )
    assert response.status_code == 200, response.text
    assert response.json() == {
        "valid": True,
        "semanticVersion": "1.0.0",
        "metricCount": 1,
        "dimensionCount": 1,
        "diagnostics": [],
    }


def test_semantic_draft_is_preview_only_and_source_references_are_checked(client):
    draft = {
        "metrics": [
            {
                "name": "event_count",
                "description": "Count each event row.",
                "expression": "COUNT(*)",
                "unit": "",
                "tableRefs": [{"schema": "analytics", "name": "events"}],
                "compatibleDimensions": ["channel"],
                "synonyms": ["event count", "số sự kiện"],
            }
        ],
        "dimensions": [],
        "businessRules": [],
        "warnings": ["Review event grain before activation."],
    }

    def provider_response(request):
        payload = json.loads(request.content)
        prompt = json.loads(payload["messages"][1]["content"])
        assert prompt["semanticContext"]["examples"] == []
        assert "private-provider-key-never-echo" not in request.content.decode()
        return httpx.Response(
            200,
            json={
                "model": "local-semantic-model",
                "choices": [{"message": {"content": json.dumps(draft)}}],
            },
        )

    app.state.provider_transport = httpx.MockTransport(provider_response)
    response = client.post(
        "/internal/v1/semantic-suggestions",
        json=request_body(),
        headers=private_headers(),
    )
    assert response.status_code == 200, response.text
    assert response.json()["metrics"][0]["name"] == "event_count"
    assert "warnings" in response.json()
    assert "private-provider-key-never-echo" not in response.text


def test_semantic_draft_with_unknown_column_fails_closed(client):
    draft = {
        "metrics": [
            {
                "name": "event_count",
                "description": "Count event rows.",
                "expression": "SUM(secret_column)",
                "unit": "",
                "tableRefs": [{"schema": "analytics", "name": "events"}],
                "compatibleDimensions": [],
                "synonyms": [],
            }
        ],
        "dimensions": [],
        "businessRules": [],
        "warnings": [],
    }
    app.state.provider_transport = httpx.MockTransport(
        lambda request: httpx.Response(
            200,
            json={"choices": [{"message": {"content": json.dumps(draft)}}]},
        )
    )
    response = client.post(
        "/internal/v1/semantic-suggestions",
        json=request_body(),
        headers=private_headers(),
    )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "INVALID_MODEL_RESPONSE"


def test_suggestion_endpoint_rejects_correlation_mismatch_before_provider(client):
    app.state.provider_transport = httpx.MockTransport(
        lambda request: pytest.fail("provider must not run for invalid correlation")
    )
    response = client.post(
        "/internal/v1/semantic-suggestions",
        json=request_body(),
        headers={
            "Authorization": "Bearer " + TOKEN,
            "X-Correlation-ID": "wrong-correlation",
        },
    )
    assert response.status_code == 400
