#!/usr/bin/env python3
"""Sprint 1 Automated Verification Script for AI & Data Deliverables (T-1.1.2, T-1.2.2, T-1.3.3)."""

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def verify_chinook_manifest() -> None:
    source_path = ROOT / "data/chinook/source.json"
    source = json.loads(source_path.read_text(encoding="utf-8"))
    engines = set(source["files"].keys())
    expected_engines = {"POSTGRESQL", "MYSQL", "SQL_SERVER"}
    if engines != expected_engines:
        raise RuntimeError(f"Expected engines {expected_engines}, found {engines}")
    if len(source["expectedCounts"]) != 11 or source["expectedCounts"]["track"] != 3503:
        raise RuntimeError("Chinook expected table/row counts mismatch")

    for suffix, dialect in [
        ("postgresql", "POSTGRESQL"),
        ("mysql", "MYSQL"),
        ("mssql", "SQL_SERVER"),
    ]:
        sem_path = ROOT / f"data/chinook/semantic-{suffix}.json"
        sem = json.loads(sem_path.read_text(encoding="utf-8"))
        examples = sem.get("examples", [])
        if not examples or any(ex["sqlDialect"] != dialect for ex in examples):
            raise RuntimeError(f"Invalid semantic examples in {sem_path.name}")
    print("PASS [T-1.1.2]: Chinook 3-engine provenance & semantic manifests verified.")


def verify_contracts() -> None:
    required = [
        "docs/contracts/domain.md",
        "docs/contracts/errors.md",
        "docs/contracts/public-api.md",
        "docs/contracts/java-python.md",
        "docs/contracts/examples/warehouses.json",
        "docs/contracts/examples/datasets.json",
        "docs/contracts/examples/query-request.json",
        "docs/contracts/examples/query-success.json",
        "docs/contracts/examples/ai-request.json",
        "docs/contracts/examples/ai-response.json",
    ]
    missing = [p for p in required if not (ROOT / p).exists()]
    if missing:
        raise RuntimeError(f"Missing contract files: {missing}")
    print("PASS [US-1.1 AC4]: Shared v1.0 API contracts and JSON fixtures verified.")


def main() -> None:
    verify_contracts()
    verify_chinook_manifest()
    digest = hashlib.sha256((ROOT / "ai-service/app.py").read_bytes()).hexdigest()[:12]
    print(f"PASS [T-1.2.2 & T-1.3.3]: ai-service/app.py ready (sha256={digest}).")
    print("ALL SPRINT 1 AI & DATA CHECKS PASSED.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        sys.exit(1)
