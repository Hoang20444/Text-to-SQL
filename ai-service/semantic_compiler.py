"""Cross-dialect business metric compiler based on the project's Wren fork.

The compiler builds query candidates from administrator-approved metadata. Java still
performs independent schema, AST, authorization, and read-only execution checks.

Source adaptation: Canner Wren Engine, ``wren/src/wren/nla_business.py``.
The Apache-2.0 notice is retained in ``ai-service/third-party/licenses``.
"""

from __future__ import annotations

import math
import re
from collections import deque
from datetime import date, datetime
from typing import Any

import sqlglot
from sqlglot import exp

DIALECTS = {
    "POSTGRESQL": "postgres",
    "MYSQL": "mysql",
    "SQL_SERVER": "tsql",
}

FUNCTIONS = {
    "sum",
    "count",
    "avg",
    "min",
    "max",
    "cast",
    "trycast",
    "coalesce",
    "nullif",
    "round",
    "abs",
    "lower",
    "upper",
    "trim",
    "length",
    "concat",
    "substring",
    "strposition",
}


class SemanticCompileError(ValueError):
    """Raised when a semantic definition cannot be compiled without guessing."""


def _key(table: dict[str, Any]) -> tuple[str, str]:
    return table["schema"], table["name"]


def _fail() -> None:
    raise SemanticCompileError("SEMANTIC_MODEL_INVALID")


class BusinessCompiler:
    """Validate approved measures and compile a bounded query for one SQL dialect."""

    def __init__(self, context: dict[str, Any], dialect: str):
        self.context = context
        if dialect not in DIALECTS:
            _fail()
        self.dialect = DIALECTS[dialect]
        self.tables = {_key(table): table for table in context["tables"]}
        self.metrics = self._named(context.get("metrics", []))
        self.dimensions = self._named(context.get("dimensions", []))
        metric_names = {name.casefold() for name in self.metrics}
        dimension_names = {name.casefold() for name in self.dimensions}
        if metric_names & dimension_names:
            _fail()
        self.aliases = {ref: f"nla_t{index}" for index, ref in enumerate(self.tables)}
        self.edges: dict[tuple[str, str], list[tuple[Any, ...]]] = {}
        for relation in context.get("relationships", []):
            left, right = relation["from"], relation["to"]
            left_key = _key(left["table"])
            right_key = _key(right["table"])
            self._column(left_key, left["column"])
            self._column(right_key, right["column"])
            cardinality = relation["cardinality"]
            if cardinality not in {
                "ONE_TO_ONE",
                "MANY_TO_ONE",
                "ONE_TO_MANY",
                "MANY_TO_MANY",
            }:
                _fail()
            if cardinality in {"MANY_TO_ONE", "ONE_TO_ONE"}:
                self.edges.setdefault(left_key, []).append(
                    (right_key, left["column"], right["column"])
                )
            if cardinality in {"ONE_TO_MANY", "ONE_TO_ONE"}:
                self.edges.setdefault(right_key, []).append(
                    (left_key, right["column"], left["column"])
                )
        for dimension in self.dimensions.values():
            self._column(_key(dimension["table"]), dimension["column"])
        for metric in self.metrics.values():
            refs = [_key(ref) for ref in metric.get("tableRefs", [])]
            if (
                not refs
                or len(refs) != len(set(refs))
                or any(ref not in self.tables for ref in refs)
            ):
                _fail()
            if not set(metric.get("compatibleDimensions", [])) <= set(self.dimensions):
                _fail()
            self._expression(metric["expression"], set(refs), aggregate=True)
            for ref in refs[1:]:
                self._path(refs[0], ref)
            for dimension_name in metric.get("compatibleDimensions", []):
                dimension = self.dimensions[dimension_name]
                self._path(refs[0], _key(dimension["table"]))

    @staticmethod
    def _named(items: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
        result: dict[str, dict[str, Any]] = {}
        for item in items:
            name = item.get("name")
            if (
                not isinstance(name, str)
                or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,199}", name)
                or any(existing.casefold() == name.casefold() for existing in result)
            ):
                _fail()
            result[name] = item
        return result

    def _column(self, ref: tuple[str, str], column: str) -> exp.Column:
        table = self.tables.get(ref)
        if table is None or column not in {item["name"] for item in table["columns"]}:
            _fail()
        return exp.column(column, table=self.aliases[ref], quoted=True)

    def _expression(
        self, text: str, refs: set[tuple[str, str]], *, aggregate: bool
    ) -> exp.Expression:
        if not isinstance(text, str) or not text or len(text) > 10000:
            _fail()
        try:
            statements = sqlglot.parse(
                "SELECT " + text,
                read=self.dialect,
                error_level="RAISE",
            )
        except Exception:
            _fail()
        if len(statements) != 1 or not isinstance(statements[0], exp.Select):
            _fail()
        query = statements[0]
        if len(query.expressions) != 1 or any(
            value for name, value in query.args.items() if name != "expressions"
        ):
            _fail()
        expression = query.expressions[0]
        if isinstance(expression, exp.Alias) or any(
            isinstance(node, (exp.Query, exp.Window, exp.Command, exp.Parameter, exp.Placeholder))
            or node.comments
            for node in expression.walk()
        ):
            _fail()
        for node in expression.find_all(exp.Func):
            name = node.name.lower() if isinstance(node, exp.Anonymous) else node.key.lower()
            if name not in FUNCTIONS:
                _fail()
        if aggregate and not any(isinstance(node, exp.AggFunc) for node in expression.walk()):
            _fail()
        for star in expression.find_all(exp.Star):
            if not isinstance(star.parent, exp.Count):
                _fail()
        for node in expression.find_all(exp.AggFunc):
            parent = node.parent
            while parent is not None:
                if isinstance(parent, exp.AggFunc):
                    _fail()
                parent = parent.parent
        for column in list(expression.find_all(exp.Column)):
            if column.catalog:
                _fail()
            candidates = []
            for ref in refs:
                if column.table and column.table != ref[1]:
                    continue
                if column.db and column.db != ref[0]:
                    continue
                if column.name in {item["name"] for item in self.tables[ref]["columns"]}:
                    candidates.append(ref)
            if len(candidates) != 1:
                _fail()
            if aggregate:
                parent = column.parent
                while parent is not None and not isinstance(parent, exp.AggFunc):
                    parent = parent.parent
                if parent is None:
                    _fail()
            column.replace(self._column(candidates[0], column.name))
        return expression

    def _path(self, source: tuple[str, str], destination: tuple[str, str]) -> list[tuple[Any, ...]]:
        if source == destination:
            return []
        queue = deque([(source, [])])
        distances = {source: 0}
        answers: list[list[tuple[Any, ...]]] = []
        while queue:
            ref, path = queue.popleft()
            if answers and len(path) >= len(answers[0]):
                continue
            for target, left, right in set(self.edges.get(ref, [])):
                step = (ref, target, left, right)
                candidate = path + [step]
                if target == destination:
                    answers.append(candidate)
                    if len(answers) > 1:
                        _fail()
                elif target not in distances or distances[target] == len(candidate):
                    distances[target] = len(candidate)
                    queue.append((target, candidate))
        if len(answers) != 1:
            _fail()
        return answers[0]

    def _table(self, ref: tuple[str, str]) -> exp.Table:
        return exp.Table(
            this=exp.to_identifier(ref[1], quoted=True),
            db=exp.to_identifier(ref[0], quoted=True),
            alias=exp.TableAlias(this=exp.to_identifier(self.aliases[ref], quoted=True)),
        )

    def compile(self, selection: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(selection, dict) or set(selection) - {
            "metrics",
            "dimensions",
            "filters",
            "limit",
        }:
            _fail()
        selected_metrics = selection.get("metrics", [])
        selected_dimensions = selection.get("dimensions", [])
        filters = selection.get("filters", [])
        limit = selection.get("limit", 100)
        if not all(
            isinstance(items, list) for items in (selected_metrics, selected_dimensions, filters)
        ):
            _fail()
        if not all(isinstance(name, str) for name in selected_metrics + selected_dimensions):
            _fail()
        for item in filters:
            if (
                not isinstance(item, dict)
                or set(item) - {"dimension", "operator", "value"}
                or item.get("dimension") not in self.dimensions
                or item.get("operator")
                not in {"EQ", "NE", "GT", "GTE", "LT", "LTE", "CONTAINS", "IS_NULL", "IS_NOT_NULL"}
            ):
                _fail()
        if (
            not 1 <= len(selected_metrics) <= 10
            or len(selected_dimensions) > 5
            or len(filters) > 10
            or not isinstance(limit, int)
            or isinstance(limit, bool)
            or not 1 <= limit <= 1000
            or len(set(selected_metrics)) != len(selected_metrics)
            or len(set(selected_dimensions)) != len(selected_dimensions)
            or not set(selected_metrics) <= self.metrics.keys()
            or not set(selected_dimensions) <= self.dimensions.keys()
        ):
            _fail()
        root = _key(self.metrics[selected_metrics[0]]["tableRefs"][0])
        for name in selected_metrics:
            metric = self.metrics[name]
            if _key(metric["tableRefs"][0]) != root:
                _fail()
            required_dimensions = set(selected_dimensions)
            required_dimensions.update(item["dimension"] for item in filters)
            if not required_dimensions <= set(metric.get("compatibleDimensions", [])):
                _fail()
        goals = {_key(self.dimensions[name]["table"]) for name in selected_dimensions}
        goals.update(_key(self.dimensions[item["dimension"]]["table"]) for item in filters)
        for name in selected_metrics:
            goals.update(_key(ref) for ref in self.metrics[name]["tableRefs"])
        joins = {}
        for goal in sorted(goals):
            for step in self._path(root, goal):
                joins[step[1]] = step
        projections = []
        groups = []
        for name in selected_dimensions:
            dimension = self.dimensions[name]
            column = self._column(_key(dimension["table"]), dimension["column"])
            projections.append(exp.alias_(column.copy(), name, quoted=True))
            groups.append(column)
        for name in selected_metrics:
            metric = self.metrics[name]
            refs = {_key(ref) for ref in metric["tableRefs"]}
            expression = self._expression(metric["expression"], refs, aggregate=True)
            projections.append(exp.alias_(expression, name, quoted=True))
        query = exp.select(*projections).from_(self._table(root))
        for parent, target, left, right in joins.values():
            query = query.join(
                self._table(target),
                join_type="LEFT",
                on=exp.EQ(
                    this=self._column(parent, left),
                    expression=self._column(target, right),
                ),
            )
        predicates = []
        for item in filters:
            dimension = self.dimensions[item["dimension"]]
            column = self._column(_key(dimension["table"]), dimension["column"])
            operator = item["operator"]
            value = item.get("value")
            self._validate_filter_value(dimension, operator, value)
            if operator in {"IS_NULL", "IS_NOT_NULL"}:
                predicate = exp.Is(this=column, expression=exp.Null())
                if operator == "IS_NOT_NULL":
                    predicate = exp.Not(this=predicate)
            elif operator == "CONTAINS" and isinstance(value, str):
                predicate = exp.GT(
                    this=exp.StrPosition(this=column, substr=exp.convert(value)),
                    expression=exp.Literal.number(0),
                )
            else:
                comparison = {
                    "EQ": exp.EQ,
                    "NE": exp.NEQ,
                    "GT": exp.GT,
                    "GTE": exp.GTE,
                    "LT": exp.LT,
                    "LTE": exp.LTE,
                }.get(operator)
                if comparison is None or value is None:
                    _fail()
                predicate = comparison(this=column, expression=exp.convert(value))
            predicates.append(predicate)
        if predicates:
            query = query.where(exp.and_(*predicates))
        if groups:
            query = query.group_by(*groups).order_by(*[group.copy() for group in groups])
        query = query.limit(limit)
        return {
            "sql": query.sql(dialect=self.dialect),
            "explanation": "Truy vấn được biên dịch từ metric, dimension và quan hệ đã cấu hình; không gọi LLM.",
            "assumptions": [
                "Chỉ dùng đường join many-to-one/one-to-one đã khai báo để giữ đúng mức hạt."
            ],
            "selectedMetrics": selected_metrics,
            "selectedDimensions": selected_dimensions,
            "chartRecommendation": {
                "type": "bar" if len(selected_dimensions) == 1 else "table",
                "title": ", ".join(selected_metrics),
                **({"x": selected_dimensions[0]} if len(selected_dimensions) == 1 else {}),
                "series": [{"column": name, "label": name} for name in selected_metrics],
            },
            "confidence": 1.0,
            "model": "semantic-compiler",
        }

    @staticmethod
    def _validate_filter_value(dimension, operator, value):
        if operator in {"IS_NULL", "IS_NOT_NULL"}:
            if value is not None:
                _fail()
            return
        if value is None or isinstance(value, (dict, list)):
            _fail()
        if isinstance(value, str) and (not value or len(value) > 2000):
            _fail()
        if isinstance(value, float) and not math.isfinite(value):
            _fail()

        data_type = dimension["dataType"].upper()
        is_text = any(token in data_type for token in ("CHAR", "TEXT", "STRING", "CLOB"))
        is_boolean = "BOOL" in data_type or data_type == "BIT"
        is_temporal = any(token in data_type for token in ("DATE", "TIME"))
        is_numeric = any(
            token in data_type
            for token in ("INT", "DECIMAL", "NUMERIC", "NUMBER", "FLOAT", "REAL", "DOUBLE", "MONEY")
        )

        if operator == "CONTAINS" and (not is_text or not isinstance(value, str)):
            _fail()
        if isinstance(value, bool):
            if not is_boolean or operator == "CONTAINS":
                _fail()
            return
        if isinstance(value, (int, float)):
            if not is_numeric:
                _fail()
            return
        if isinstance(value, str):
            if is_numeric or is_boolean:
                _fail()
            if is_temporal:
                try:
                    if "DATE" in data_type and "TIME" not in data_type:
                        date.fromisoformat(value)
                    else:
                        datetime.fromisoformat(value.replace("Z", "+00:00"))
                except ValueError:
                    _fail()
            elif not is_text:
                _fail()
            return
        _fail()
