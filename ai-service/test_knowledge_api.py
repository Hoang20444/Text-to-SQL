"""Private knowledge lifecycle and current-schema retrieval tests."""

import json

import httpx
import pytest
from fastapi.testclient import TestClient

import app

TOKEN = "knowledge-tests-private-service-token-with-32-characters"


def context():
    return {
        "tables": [
            {
                "schema": "analytics",
                "name": "events",
                "columns": [
                    {"name": "id", "dataType": "BIGINT"},
                    {"name": "amount", "dataType": "DECIMAL"},
                ],
            }
        ],
        "metrics": [],
        "dimensions": [],
        "relationships": [],
        "examples": [],
        "businessRules": [],
        "semanticVersion": "1.0.0",
        "schemaVersion": "schema-1",
        "timezone": "UTC",
        "referenceTime": "2026-11-19T00:00:00Z",
    }


def request_body(**overrides):
    body = {
        "correlationId": "knowledge-tests",
        "warehouseId": "wh-authorized-by-java",
        "datasetId": "ds-authorized-by-java",
        "sqlDialect": "POSTGRESQL",
        "semanticContext": context(),
    }
    body.update(overrides)
    return body


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setenv("PYTHON_AI_TOKEN", TOKEN)
    monkeypatch.setenv("KNOWLEDGE_ROOT", str(tmp_path / "knowledge"))
    monkeypatch.setenv("KNOWLEDGE_EMBEDDING_URL", "http://127.0.0.1:11434/v1")
    monkeypatch.setenv("KNOWLEDGE_EMBEDDING_MODEL", "embeddinggemma:300m")
    with TestClient(app.app) as test_client:
        yield test_client


def headers(correlation="knowledge-tests"):
    return {
        "Authorization": "Bearer " + TOKEN,
        "X-Correlation-ID": correlation,
    }


def test_knowledge_endpoint_requires_private_service_auth(client):
    response = client.post(
        "/internal/v1/knowledge/status",
        json=request_body(),
        headers={"X-Correlation-ID": "knowledge-tests"},
    )
    assert response.status_code == 401


def test_knowledge_endpoint_rejects_correlation_mismatch(client):
    response = client.post(
        "/internal/v1/knowledge/status",
        json=request_body(),
        headers=headers("different-correlation"),
    )
    assert response.status_code == 400


def test_knowledge_status_is_dataset_scoped_and_does_not_return_path(client, monkeypatch):
    from knowledge import DatasetKnowledge

    calls = []

    class FakeKnowledge:
        def __init__(self, warehouse_id, dataset_id, dialect, semantic_context):
            calls.append((warehouse_id, dataset_id, dialect, semantic_context["schemaVersion"]))

        def status(self):
            return {"datasetId": "ds-authorized-by-java", "status": "EMPTY", "exampleCount": 0}

    monkeypatch.setattr("knowledge.DatasetKnowledge", FakeKnowledge)
    response = client.post(
        "/internal/v1/knowledge/status",
        json=request_body(),
        headers=headers(),
    )
    assert response.status_code == 200, response.text
    assert response.json()["status"] == "EMPTY"
    assert "path" not in response.text
    assert calls == [("wh-authorized-by-java", "ds-authorized-by-java", "POSTGRESQL", "schema-1")]
    assert DatasetKnowledge is not None


def test_remember_accepts_only_single_read_query_over_current_dataset_tables(client, monkeypatch):
    from knowledge import DatasetKnowledge

    remembered = []

    class FakeKnowledge:
        def __init__(self, *args):
            pass

        def remember(self, example):
            remembered.append(example)
            return {"items": [], "total": 1}

    monkeypatch.setattr("knowledge.DatasetKnowledge", FakeKnowledge)
    good = client.post(
        "/internal/v1/knowledge/remember",
        json=request_body(
            example={
                "question": "Count events",
                "sql": "SELECT COUNT(*) AS n FROM analytics.events",
            }
        ),
        headers=headers(),
    )
    assert good.status_code == 200, good.text
    assert len(remembered) == 1
    assert "COUNT(*)" in remembered[0]["sql"]

    for unsafe in (
        "DELETE FROM analytics.events",
        "SELECT * FROM secret.events",
        "SELECT COUNT(*) FROM analytics.events; DROP TABLE analytics.events",
        "SELECT COUNT(*) FROM pg_catalog.pg_class",
    ):
        rejected = client.post(
            "/internal/v1/knowledge/remember",
            json=request_body(example={"question": "Unsafe", "sql": unsafe}),
            headers=headers(),
        )
        assert rejected.status_code == 422
    assert len(remembered) == 1
    assert DatasetKnowledge is not None


def test_recalled_approved_example_is_added_to_current_context(monkeypatch):
    from knowledge import DatasetKnowledge

    class FakeKnowledge:
        def __init__(self, warehouse_id, dataset_id, dialect, semantic_context):
            assert semantic_context["schemaVersion"] == "schema-1"

        def status(self):
            return {"status": "READY"}

        def search(self, question, limit):
            assert question == "How many events?"
            assert limit == 3
            return {
                "examples": [
                    {
                        "question": "Count events",
                        "sql": "SELECT COUNT(*) AS n FROM analytics.events",
                        "sqlDialect": "POSTGRESQL",
                    }
                ]
            }

    monkeypatch.setattr("knowledge.DatasetKnowledge", FakeKnowledge)
    request = app.Input.model_validate(request_body(question="How many events?"))
    augmented = app.retrieve_approved_examples(request)
    assert len(augmented.semanticContext.examples) == 1
    assert augmented.semanticContext.examples[0].question == "Count events"
    assert (
        app.retrieve_approved_examples(augmented).semanticContext.examples
        == augmented.semanticContext.examples
    )
