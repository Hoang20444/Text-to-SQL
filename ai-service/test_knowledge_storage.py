"""Exercise the dataset knowledge adapter against the real Wren/LanceDB store."""

import hashlib
import json
import math
from copy import deepcopy

import httpx
import pytest

import knowledge


class DeterministicEmbeddings:
    def __init__(self, *, operation_timeout=20):
        self.model = "test-local-embedding"

    def compute_source_embeddings(self, texts):
        vectors = []
        for text in texts:
            digest = hashlib.sha256(text.casefold().encode("utf-8")).digest()
            values = [((byte / 127.5) - 1) for byte in digest]
            expanded = (values * math.ceil(384 / len(values)))[:384]
            vectors.append(expanded)
        return vectors

    def compute_query_embeddings(self, query):
        return self.compute_source_embeddings([query] if isinstance(query, str) else query)


@pytest.fixture
def memory_root(tmp_path, monkeypatch):
    monkeypatch.setenv("KNOWLEDGE_ROOT", str(tmp_path / "knowledge"))
    monkeypatch.setattr(knowledge, "LocalEmbeddings", DeterministicEmbeddings)


def context(semantic_version="1.0.0", schema_version="schema-1"):
    return {
        "tables": [
            {
                "schema": "analytics",
                "name": "events",
                "description": "Business events. " + ("Authorized schema metadata. " * 300),
                "columns": [
                    {"name": "id", "dataType": "BIGINT", "properties": {}},
                    {"name": "amount", "dataType": "DECIMAL", "properties": {}},
                    *[
                        {
                            "name": f"event_attribute_{index:04d}",
                            "dataType": "VARCHAR",
                            "properties": {
                                "description": (
                                    f"Business event attribute number {index} used for "
                                    "classification, audit, and analytical filtering."
                                )
                            },
                        }
                        for index in range(600)
                    ],
                ],
            }
        ],
        "metrics": [],
        "dimensions": [],
        "relationships": [],
        "semanticMetrics": [],
        "semanticDimensions": [],
        "businessRules": [],
        "semanticVersion": semantic_version,
        "schemaVersion": schema_version,
    }


def make_knowledge(dataset_id="dataset-1", semantic_version="1.0.0", schema_version="schema-1"):
    return knowledge.DatasetKnowledge(
        "warehouse-1",
        dataset_id,
        "POSTGRESQL",
        context(semantic_version, schema_version),
    )


def test_wren_memory_index_approval_search_forget_and_reset(memory_root):
    memory = make_knowledge()

    indexed = memory.index()

    assert indexed["status"] == "READY"
    assert indexed["schemaItems"] >= 1
    assert memory.status()["exampleCount"] == 0

    result = memory.remember(
        {
            "question": "How many events?",
            "sql": "SELECT COUNT(*) AS event_count FROM analytics.events",
        }
    )
    assert result["total"] == 1
    entry_id = result["items"][0]["entryId"]

    search_result = memory.search("How many events?", limit=5)
    assert search_result["strategy"] == "search"
    assert search_result["datasetId"] == "dataset-1"
    assert search_result["hits"]
    assert search_result["examples"][0]["entryId"] == entry_id

    assert memory.forget(entry_id)["total"] == 0
    assert memory.reset()["status"] == "EMPTY"


def test_knowledge_state_isolated_by_dataset(memory_root):
    first = make_knowledge(dataset_id="dataset-1")
    second = make_knowledge(dataset_id="dataset-2")

    first.index()

    assert first.status()["status"] == "READY"
    assert second.status()["status"] == "EMPTY"


def test_semantic_change_marks_index_stale_and_blocks_search(memory_root):
    original = make_knowledge()
    original.index()
    updated = make_knowledge(semantic_version="1.0.1")

    assert updated.status()["status"] == "STALE"
    with pytest.raises(knowledge.KnowledgeError):
        updated.search("How many events?")


def test_failed_rebuild_preserves_previous_lancedb_generation(memory_root, monkeypatch):
    memory = make_knowledge()
    memory.index()
    previous_state = deepcopy(memory._state())

    def failed_embedding(self, texts):
        raise RuntimeError("provider token must never be returned")

    monkeypatch.setattr(DeterministicEmbeddings, "compute_source_embeddings", failed_embedding)
    with pytest.raises(knowledge.KnowledgeError) as error:
        memory.index()

    assert "provider token" not in str(error.value)
    assert memory._state() == previous_state
    assert memory.status()["status"] == "READY"


def test_query_embedding_is_reused_during_schema_and_example_retrieval(monkeypatch):
    requests = []
    original_client = httpx.Client

    def handler(request):
        requests.append(request)
        payload = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "data": [
                    {"index": index, "embedding": [0.1] * 384}
                    for index, _ in enumerate(payload["input"])
                ]
            },
        )

    transport = httpx.MockTransport(handler)

    def client_factory(**kwargs):
        return original_client(transport=transport, **kwargs)

    monkeypatch.setattr(knowledge.httpx, "Client", client_factory)
    monkeypatch.setenv("KNOWLEDGE_EMBEDDING_URL", "http://127.0.0.1:11434/v1")

    embedding = knowledge.LocalEmbeddings()
    first = embedding.compute_query_embeddings("How many events?")
    second = embedding.compute_query_embeddings("How many events?")

    assert first == second
    assert len(requests) == 1
