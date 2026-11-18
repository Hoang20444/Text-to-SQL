"""Sprint 3 business semantic compilation and validation boundaries."""

from copy import deepcopy

import pytest
import sqlglot
from fastapi.testclient import TestClient
from sqlglot import exp

from app import app
from wren.nla_business import BusinessCompiler
from wren.nla_planning import DatasetPlanningError

TOKEN = "workbench-tests-private-service-token-32-characters"
DIALECTS = ["POSTGRESQL", "MYSQL", "SQL_SERVER"]


def semantic_context():
    table = {"schema": "analytics", "name": "events"}
    dimension_table = {"schema": "analytics", "name": "channels"}
    return {
        "tables": [
            {
                **table,
                "columns": [
                    {"name": "id", "dataType": "BIGINT"},
                    {"name": "channel_id", "dataType": "BIGINT"},
                    {"name": "amount", "dataType": "DECIMAL"},
                ],
            },
            {
                **dimension_table,
                "columns": [
                    {"name": "id", "dataType": "BIGINT"},
                    {"name": "label", "dataType": "STRING"},
                ],
            },
        ],
        "metrics": [
            {
                "name": "total_value",
                "expression": "SUM(amount)",
                "tableRefs": [table],
                "compatibleDimensions": ["channel"],
            }
        ],
        "dimensions": [
            {
                "name": "channel",
                "table": dimension_table,
                "column": "label",
                "dataType": "STRING",
            }
        ],
        "relationships": [
            {
                "from": {"table": table, "column": "channel_id"},
                "to": {"table": dimension_table, "column": "id"},
                "cardinality": "MANY_TO_ONE",
            }
        ],
        "examples": [
            {
                "question": "How many events?",
                "sqlDialect": "POSTGRESQL",
                "sql": 'SELECT COUNT(*) AS count FROM "analytics"."events"',
            }
        ],
        "businessRules": ["Value measures the configured amount before adjustments."],
        "semanticVersion": "semantic-a",
        "schemaVersion": "schema-a",
        "timezone": "UTC",
        "referenceTime": "2026-10-21T00:00:00Z",
    }


def envelope(dialect="POSTGRESQL"):
    return {
        "correlationId": "workbench-test",
        "warehouseId": "authorized-warehouse",
        "datasetId": "authorized-dataset",
        "sqlDialect": dialect,
        "semanticContext": semantic_context(),
    }


def headers():
    return {"Authorization": "Bearer " + TOKEN, "X-Correlation-ID": "workbench-test"}


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("PYTHON_AI_TOKEN", TOKEN)
    with TestClient(app) as value:
        yield value


@pytest.mark.parametrize("dialect", DIALECTS)
def test_business_compiles_typed_filters_and_safe_grain(dialect):
    result = BusinessCompiler(semantic_context(), dialect).compile(
        {
            "metrics": ["total_value"],
            "dimensions": ["channel"],
            "filters": [{"dimension": "channel", "operator": "CONTAINS", "value": "' OR 1=1"}],
            "limit": 25,
        }
    )
    read = {"POSTGRESQL": "postgres", "MYSQL": "mysql", "SQL_SERVER": "tsql"}[dialect]
    ast = sqlglot.parse_one(result["sql"], read=read)
    assert [table.name for table in ast.find_all(exp.Table)] == ["events", "channels"]
    assert ast.args["joins"][0].side == "LEFT"
    assert ast.args["group"] is not None
    assert result["selectedMetrics"] == ["total_value"]
    assert result["model"] == "semantic-compiler"
    assert "25" in ast.args["limit"].sql()
    assert any(literal.this == "' OR 1=1" for literal in ast.find_all(exp.Literal))


@pytest.mark.parametrize("cardinality", ["ONE_TO_MANY", "MANY_TO_MANY", None])
def test_business_rejects_unsafe_declared_join_grain(cardinality):
    context = semantic_context()
    context["relationships"][0]["cardinality"] = cardinality
    with pytest.raises(DatasetPlanningError):
        BusinessCompiler(context, "POSTGRESQL")


def test_business_rejects_ambiguous_parallel_foreign_keys():
    context = semantic_context()
    second = deepcopy(context["relationships"][0])
    second["from"]["column"] = "id"
    context["relationships"].append(second)
    with pytest.raises(DatasetPlanningError):
        BusinessCompiler(context, "POSTGRESQL")


@pytest.mark.parametrize(
    "expression",
    [
        "amount",
        "amount + SUM(amount)",
        "SUM(missing)",
        "SUM(amount) FROM secret.table",
        "SUM(amount) ORDER BY amount",
        "SUM(amount); DELETE FROM events",
        "(SELECT SUM(amount) FROM events)",
        "SUM(SUM(amount))",
        "SUM(amount) OVER ()",
        "pg_sleep(1)",
        "COUNT(*) /* hide injected instructions */",
    ],
)
def test_business_rejects_invalid_measure_expression(expression):
    context = semantic_context()
    context["metrics"][0]["expression"] = expression
    with pytest.raises(DatasetPlanningError):
        BusinessCompiler(context, "POSTGRESQL")


@pytest.mark.parametrize(
    "selection",
    [
        {"metrics": ["unknown"]},
        {"metrics": ["total_value", "total_value"]},
        {"metrics": ["total_value"], "limit": True},
        {"metrics": ["total_value"], "filters": [{"dimension": "missing", "operator": "EQ"}]},
        {"metrics": ["total_value"], "filters": [{"dimension": "channel", "operator": "SQL"}]},
        {
            "metrics": ["total_value"],
            "filters": [{"dimension": "channel", "operator": "IS_NULL", "value": "x"}],
        },
        {
            "metrics": ["total_value"],
            "filters": [{"dimension": "channel", "operator": "EQ", "value": float("nan")}],
        },
    ],
)
def test_business_invalid_selection_has_controlled_error(selection):
    with pytest.raises(DatasetPlanningError):
        BusinessCompiler(semantic_context(), "POSTGRESQL").compile(selection)


@pytest.mark.parametrize("dialect", DIALECTS)
def test_private_routes_accept_json_enum_and_reject_unknown_filter(client, dialect):
    response = client.post(
        "/internal/v1/business-validate", headers=headers(), json=envelope(dialect)
    )
    assert response.status_code == 200, response.text
    response = client.post(
        "/internal/v1/business-plan",
        headers=headers(),
        json={
            **envelope(dialect),
            "selection": {"metrics": ["total_value"], "dimensions": ["channel"]},
        },
    )
    assert response.status_code == 200, response.text
    assert response.json()["model"] == "semantic-compiler"
    response = client.post(
        "/internal/v1/business-plan",
        headers=headers(),
        json={
            **envelope(dialect),
            "selection": {
                "metrics": ["total_value"],
                "filters": [{"dimension": "typo", "operator": "EQ", "value": "x"}],
            },
        },
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "SEMANTIC_MODEL_INVALID"


def test_private_workbench_auth_and_correlation(client):
    for path in ["business-plan", "business-validate"]:
        assert client.post("/internal/v1/" + path, json=envelope()).status_code == 401
    response = client.post(
        "/internal/v1/business-validate",
        headers={**headers(), "X-Correlation-ID": "wrong"},
        json=envelope(),
    )
    assert response.status_code == 400
