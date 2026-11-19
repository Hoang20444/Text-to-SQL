"""Verify the three Chinook semantic manifests and SQL examples are internally consistent."""

from __future__ import annotations

import json
from pathlib import Path

import sqlglot
from sqlglot import exp


ROOT = Path(__file__).resolve().parents[2]
MANIFESTS = {
    "POSTGRESQL": ROOT / "data/chinook/semantic-postgresql.json",
    "MYSQL": ROOT / "data/chinook/semantic-mysql.json",
    "SQL_SERVER": ROOT / "data/chinook/semantic-mssql.json",
}
READ_DIALECTS = {
    "POSTGRESQL": "postgres",
    "MYSQL": "mysql",
    "SQL_SERVER": "tsql",
}


def load_manifests():
    manifests = {}
    for dialect, path in MANIFESTS.items():
        document = json.loads(path.read_text(encoding="utf-8"))
        metrics = document.get("metrics")
        dimensions = document.get("dimensions")
        examples = document.get("examples")
        if not isinstance(metrics, list) or not isinstance(dimensions, list):
            raise ValueError(f"Semantic collections are missing: {path.name}")
        if not isinstance(examples, list) or not examples:
            raise ValueError(f"No approved examples are available: {path.name}")
        manifests[dialect] = document
    return manifests


def validate_manifest(dialect: str, document: dict):
    metrics = {item["name"]: item for item in document["metrics"]}
    dimensions = {item["name"]: item for item in document["dimensions"]}
    if len(metrics) != len(document["metrics"]):
        raise ValueError(f"Duplicate metric name in {dialect}")
    if len(dimensions) != len(document["dimensions"]):
        raise ValueError(f"Duplicate dimension name in {dialect}")
    if {name.casefold() for name in metrics} & {name.casefold() for name in dimensions}:
        raise ValueError(f"Metric/dimension name collision in {dialect}")
    for metric in metrics.values():
        refs = metric.get("tableRefs")
        compatible = metric.get("compatibleDimensions")
        if not refs or not isinstance(compatible, list) or not set(compatible) <= dimensions.keys():
            raise ValueError(f"Invalid metric references in {dialect}: {metric['name']}")
        try:
            parsed = sqlglot.parse("SELECT " + metric["expression"], read=READ_DIALECTS[dialect])
        except Exception as error:
            raise ValueError(f"Invalid metric expression in {dialect}: {metric['name']}") from error
        if len(parsed) != 1 or not isinstance(parsed[0], exp.Select):
            raise ValueError(f"Metric is not a scalar SELECT expression in {dialect}")
        if not any(isinstance(node, exp.AggFunc) for node in parsed[0].walk()):
            raise ValueError(f"Metric is not aggregate in {dialect}: {metric['name']}")
    for dimension in dimensions.values():
        if not dimension.get("table") or not dimension.get("column"):
            raise ValueError(f"Dimension is missing a physical reference in {dialect}")
    for example in document["examples"]:
        if example.get("sqlDialect") != dialect:
            raise ValueError(f"Example dialect mismatch in {dialect}")
        selected_metrics = example.get("selectedMetrics", [])
        selected_dimensions = example.get("selectedDimensions", [])
        if not set(selected_metrics) <= metrics.keys() or not set(selected_dimensions) <= dimensions.keys():
            raise ValueError(f"Example references an unknown semantic name in {dialect}")
        try:
            statements = sqlglot.parse(example["sql"], read=READ_DIALECTS[dialect])
        except Exception as error:
            raise ValueError(f"Invalid SQL example in {dialect}") from error
        if len(statements) != 1 or not isinstance(statements[0], exp.Query):
            raise ValueError(f"SQL example is not one read query in {dialect}")
        if ";" in example["sql"].rstrip().rstrip(";"):
            raise ValueError(f"Multiple SQL statements are not allowed in {dialect}")


def main():
    manifests = load_manifests()
    for dialect, document in manifests.items():
        validate_manifest(dialect, document)
    baseline = manifests["POSTGRESQL"]
    expected_questions = {item["question"] for item in baseline["examples"]}
    expected_metrics = {item["name"] for item in baseline["metrics"]}
    expected_dimensions = {item["name"] for item in baseline["dimensions"]}
    for dialect, document in manifests.items():
        if {item["question"] for item in document["examples"]} != expected_questions:
            raise ValueError(f"Cross-dialect question coverage differs in {dialect}")
        if {item["name"] for item in document["metrics"]} != expected_metrics:
            raise ValueError(f"Cross-dialect metrics differ in {dialect}")
        if {item["name"] for item in document["dimensions"]} != expected_dimensions:
            raise ValueError(f"Cross-dialect dimensions differ in {dialect}")
    print(
        "PASS [Sprint 3 Data]: three dialect manifests, semantic references, approved examples, "
        "and equivalent question coverage are valid."
    )


if __name__ == "__main__":
    main()
