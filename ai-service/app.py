"""Private generation service. Never stores warehouse credentials or executes SQL."""

import hmac
import json
import logging
import os
from enum import Enum
from typing import Any, Literal

from fastapi import FastAPI, Header, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

logger = logging.getLogger(__name__)


class Dialect(str, Enum):
    POSTGRESQL = "POSTGRESQL"
    MYSQL = "MYSQL"
    SQL_SERVER = "SQL_SERVER"


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, validate_by_name=True)


class TableRef(Strict):
    schema_: str = Field(alias="schema", min_length=1, max_length=256)
    name: str = Field(min_length=1, max_length=256)


class RelationshipEndpoint(Strict):
    table: TableRef
    column: str = Field(min_length=1, max_length=256)


Cardinality = Literal["ONE_TO_ONE", "MANY_TO_ONE", "ONE_TO_MANY", "MANY_TO_MANY"]


class ForeignKey(Strict):
    from_: RelationshipEndpoint = Field(alias="from")
    to: RelationshipEndpoint
    cardinality: Cardinality | None = None


class Column(Strict):
    name: str = Field(min_length=1, max_length=256)
    dataType: str = Field(min_length=1, max_length=100)
    nullable: bool | None = None
    description: str = ""
    synonyms: list[str] = Field(default_factory=list, max_length=100)


class Table(Strict):
    tableId: str | None = None
    schema_: str = Field(alias="schema", min_length=1, max_length=256)
    name: str = Field(min_length=1, max_length=256)
    logicalName: str | None = None
    description: str = ""
    grain: str = ""
    columns: list[Column] = Field(min_length=1, max_length=1000)
    queryable: bool | None = None
    category: str | None = None
    status: str | None = None
    primaryKey: list[str] = Field(default_factory=list)
    foreignKeys: list[ForeignKey] = Field(default_factory=list, max_length=5000)


class Metric(Strict):
    name: str = Field(min_length=1, max_length=256)
    description: str = ""
    expression: str = Field(min_length=1, max_length=4000)
    unit: str = ""
    tableRefs: list[TableRef] = Field(min_length=1, max_length=100)
    compatibleDimensions: list[str] = Field(default_factory=list, max_length=100)
    synonyms: list[str] = Field(default_factory=list, max_length=100)
    category: str | None = None


class Dimension(Strict):
    name: str = Field(min_length=1, max_length=256)
    description: str = ""
    table: TableRef
    column: str = Field(min_length=1, max_length=256)
    dataType: str = Field(min_length=1, max_length=100)
    synonyms: list[str] = Field(default_factory=list, max_length=100)
    category: str | None = None


class Relationship(Strict):
    from_: RelationshipEndpoint = Field(alias="from")
    to: RelationshipEndpoint
    cardinality: Cardinality | None = None


class Example(Strict):
    question: str = Field(min_length=1, max_length=1000)
    sqlDialect: str
    sql: str = Field(min_length=1, max_length=100000)
    selectedMetrics: list[str] = Field(default_factory=list, max_length=100)
    selectedDimensions: list[str] = Field(default_factory=list, max_length=100)
    chartRecommendation: dict[str, Any] | None = None
    language: str | None = None
    category: str | None = None


class Series(Strict):
    column: str = Field(min_length=1, max_length=256)
    label: str = Field(min_length=1, max_length=500)


class Chart(Strict):
    type: str = Field(min_length=1, max_length=40)
    title: str = Field(default="Kết quả truy vấn", max_length=500)
    x: str | None = Field(default=None, max_length=256)
    series: list[Series] = Field(default_factory=list, max_length=50)
    reason: str | None = Field(default=None, max_length=1000)


class Candidate(Strict):
    sql: str = Field(min_length=1, max_length=100000)
    explanation: str = Field(max_length=10000)
    assumptions: list[str] = Field(max_length=100)
    selectedMetrics: list[str] = Field(max_length=100)
    selectedDimensions: list[str] = Field(max_length=100)
    chartRecommendation: Chart
    confidence: float = Field(ge=0, le=1)
    model: str = Field(min_length=1, max_length=500)


class Context(Strict):
    tables: list[Table] = Field(max_length=1000)
    metrics: list[Metric] = Field(max_length=1000)
    dimensions: list[Dimension] = Field(max_length=1000)
    relationships: list[Relationship] = Field(max_length=5000)
    examples: list[Example] = Field(max_length=5000)
    businessRules: list[str] = Field(max_length=1000)
    semanticVersion: str = Field(min_length=1, max_length=100)
    schemaVersion: str = Field(min_length=1, max_length=100)
    timezone: str = Field(min_length=1, max_length=100)
    referenceTime: str = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def validate_references(self):
        if not self.tables:
            return self
        table_columns = {
            (table.schema_, table.name): {column.name for column in table.columns}
            for table in self.tables
        }
        if len(table_columns) != len(self.tables):
            raise ValueError("Duplicate tables in semantic context")
        if any(
            len(columns) != len(table.columns)
            for table, columns in zip(self.tables, table_columns.values())
        ):
            raise ValueError("Duplicate columns in semantic context")

        metric_names = {metric.name for metric in self.metrics}
        dimension_names = {dimension.name for dimension in self.dimensions}
        if len(metric_names) != len(self.metrics) or len(dimension_names) != len(self.dimensions):
            raise ValueError("Duplicate semantic names")
        for metric in self.metrics:
            if any((ref.schema_, ref.name) not in table_columns for ref in metric.tableRefs):
                raise ValueError("Metric references an unknown table")
            if not set(metric.compatibleDimensions) <= dimension_names:
                raise ValueError("Metric references an unknown dimension")
        for dimension in self.dimensions:
            if dimension.column not in table_columns.get(
                (dimension.table.schema_, dimension.table.name), set()
            ):
                raise ValueError("Dimension references an unknown column")
        for relationship in self.relationships:
            for endpoint in (relationship.from_, relationship.to):
                if endpoint.column not in table_columns.get(
                    (endpoint.table.schema_, endpoint.table.name), set()
                ):
                    raise ValueError("Relationship references an unknown column")
        for example in self.examples:
            if example.sqlDialect not in {dialect.value for dialect in Dialect}:
                raise ValueError("Example uses an unsupported SQL dialect")
            if not set(example.selectedMetrics) <= metric_names:
                raise ValueError("Example references an unknown metric")
            if not set(example.selectedDimensions) <= dimension_names:
                raise ValueError("Example references an unknown dimension")
        return self


class Input(Strict):
    correlationId: str = Field(min_length=1, max_length=100)
    warehouseId: str = Field(min_length=1, max_length=200)
    datasetId: str = Field(min_length=1, max_length=200)
    sqlDialect: Dialect = Field(strict=False)
    question: str = Field(min_length=1, max_length=1000)
    semanticContext: Context


class Controlled(Exception):
    def __init__(self, code: str, status: int = 422):
        self.code = code
        self.status = status


app = FastAPI(title="Private Text-to-SQL service", docs_url=None, redoc_url=None, openapi_url=None)


@app.exception_handler(Controlled)
async def controlled(req: Request, e: Controlled):
    correlation = req.headers.get("x-correlation-id", "")[:100]
    return JSONResponse(
        status_code=e.status,
        content={
            "error": {
                "code": e.code,
                "message": "Generation could not be completed safely.",
                "correlationId": correlation,
                "retryable": e.code == "PROVIDER_UNAVAILABLE",
                "details": {},
            }
        },
    )


def verify_service_token(authorization: str) -> None:
    token = os.getenv("PYTHON_AI_TOKEN", "")
    if len(token) < 32 or not hmac.compare_digest(authorization, "Bearer " + token):
        raise Controlled("UNAUTHENTICATED", 401)


import re
import unicodedata


def sql_code_tokens(sql: str) -> list[str]:
    """Return unquoted SQL tokens while rejecting comments and extra statements."""
    tokens: list[str] = []
    index = 0
    quote: str | None = None
    bracket = False
    semicolon_seen = False
    while index < len(sql):
        char = sql[index]
        following = sql[index + 1] if index + 1 < len(sql) else ""
        if quote:
            if char == quote:
                if following == quote:
                    index += 2
                    continue
                quote = None
            index += 1
            continue
        if bracket:
            if char == "]":
                if following == "]":
                    index += 2
                    continue
                bracket = False
            index += 1
            continue
        if char in {"'", '"', "`"}:
            quote = char
            index += 1
            continue
        if char == "[":
            bracket = True
            index += 1
            continue
        if char == "-" and following == "-" or char == "/" and following == "*":
            raise Controlled("INVALID_MODEL_RESPONSE", 502)
        if char == "#":
            raise Controlled("INVALID_MODEL_RESPONSE", 502)
        if char == ";":
            if semicolon_seen or sql[index + 1 :].strip():
                raise Controlled("INVALID_MODEL_RESPONSE", 502)
            semicolon_seen = True
            index += 1
            continue
        if semicolon_seen and not char.isspace():
            raise Controlled("INVALID_MODEL_RESPONSE", 502)
        if char.isalpha() or char == "_":
            end = index + 1
            while end < len(sql) and (sql[end].isalnum() or sql[end] in "_$#"):
                end += 1
            tokens.append(sql[index:end].casefold())
            index = end
            continue
        index += 1
    if quote or bracket:
        raise Controlled("INVALID_MODEL_RESPONSE", 502)
    return tokens


def normalize(s: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", s).casefold().strip().rstrip("?!.").split())


def validate(candidate: Candidate, context: Context | dict[str, Any], dialect: str) -> Candidate:
    if isinstance(context, Context):
        tables = [table.model_dump(by_alias=True) for table in context.tables]
        metrics = [metric.model_dump() for metric in context.metrics]
        dimensions = [dimension.model_dump() for dimension in context.dimensions]
    else:
        tables = context["tables"]
        metrics = context["metrics"]
        dimensions = context["dimensions"]
    if candidate.chartRecommendation.type not in {
        "table",
        "number",
        "bar",
        "grouped-bar",
        "line",
        "area",
        "pie",
    }:
        raise Controlled("INVALID_MODEL_RESPONSE", 502)
    if not set(candidate.selectedMetrics) <= {metric["name"] for metric in metrics}:
        raise Controlled("INVALID_MODEL_RESPONSE", 502)
    if not set(candidate.selectedDimensions) <= {dimension["name"] for dimension in dimensions}:
        raise Controlled("INVALID_MODEL_RESPONSE", 502)
    tokens = sql_code_tokens(candidate.sql)
    if not tokens or tokens[0] not in {"select", "with"}:
        raise Controlled("INVALID_MODEL_RESPONSE", 502)
    forbidden = {
        "alter",
        "attach",
        "backup",
        "call",
        "copy",
        "create",
        "delete",
        "detach",
        "drop",
        "execute",
        "exec",
        "grant",
        "insert",
        "into",
        "merge",
        "openquery",
        "openrowset",
        "opendatasource",
        "outfile",
        "prepare",
        "read",
        "reconfigure",
        "restore",
        "revoke",
        "shutdown",
        "truncate",
        "update",
        "use",
        "vacuum",
        "write",
    }
    forbidden_functions = {
        "benchmark",
        "load_file",
        "pg_read_file",
        "pg_read_binary_file",
        "pg_sleep",
        "sleep",
        "xp_cmdshell",
        "xp_dirtree",
        "sys_exec",
        "dblink",
        "lo_import",
        "load_extension",
        "utl_http",
        "utl_file",
    }
    if forbidden.intersection(tokens) or forbidden_functions.intersection(tokens):
        raise Controlled("INVALID_MODEL_RESPONSE", 502)
    if dialect == "SQL_SERVER" and "limit" in tokens:
        raise Controlled("SQL_DIALECT_ERROR", 502)
    if dialect != "SQL_SERVER" and re.search(r"\bSELECT\s+TOP(?:\s|\()", candidate.sql, re.I):
        raise Controlled("SQL_DIALECT_ERROR", 502)
    if dialect != "MYSQL" and "`" in candidate.sql:
        raise Controlled("SQL_DIALECT_ERROR", 502)
    available_columns = {column["name"] for table in tables for column in table["columns"]}
    available_columns.update(metric["name"] for metric in metrics)
    available_columns.update(dimension["name"] for dimension in dimensions)
    output_aliases = {
        alias.strip('"`[]').casefold()
        for alias in re.findall(
            r"\bAS\s+(\[[^]]+\]|`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$#]*)",
            candidate.sql,
            re.IGNORECASE,
        )
    }
    available_columns.update(output_aliases)
    chart = candidate.chartRecommendation
    if chart.x is not None and chart.x not in available_columns:
        raise Controlled("INVALID_MODEL_RESPONSE", 502)
    if any(series.column not in available_columns for series in chart.series):
        raise Controlled("INVALID_MODEL_RESPONSE", 502)
    if candidate.confidence < 0.5:
        raise Controlled("LOW_CONFIDENCE")
    return candidate


from copy import deepcopy
from typing import Protocol

from context_index import get_schema_index


class TextToSqlModelClient(Protocol):
    async def generate(self, request: Input, context: dict[str, Any]) -> Candidate: ...


class DeterministicModelClient:
    async def generate(self, request: Input, context: dict[str, Any]) -> Candidate:
        matches = [
            e
            for e in context["examples"]
            if e["sqlDialect"] == request.sqlDialect
            and normalize(e["question"]) == normalize(request.question)
        ]
        if len(matches) > 1:
            raise Controlled("LOW_CONFIDENCE")
        if not matches:
            raise Controlled("UNSUPPORTED_QUESTION")
        e = matches[0]
        metrics = e["selectedMetrics"]
        dims = e["selectedDimensions"]
        chart = e.get("chartRecommendation") or {
            "type": "table",
            "title": "Kết quả truy vấn",
            "series": [],
        }
        return Candidate(
            sql=e["sql"],
            explanation="Chạy mẫu ngữ nghĩa đã khai báo khớp chính xác câu hỏi và dialect. Kết quả được Java đọc từ warehouse thật.",
            assumptions=["Deterministic offline: chỉ hỗ trợ câu hỏi mẫu đã khai báo."],
            selectedMetrics=metrics,
            selectedDimensions=dims,
            chartRecommendation=Chart.model_validate(chart),
            confidence=1.0,
            model="deterministic-semantic-examples",
        )


class SemanticContextSelector:
    def select(self, request: Input, *, offline: bool = False) -> dict[str, Any]:
        try:
            budget = int(os.getenv("LLM_REQUEST_MAX_BYTES", "48000"))
        except ValueError:
            raise Controlled("INSUFFICIENT_CONTEXT")
        if budget < 1000:
            raise Controlled("INSUFFICIENT_CONTEXT")

        index = get_schema_index(request.semanticContext.model_dump(by_alias=True))
        source = deepcopy(index["context"])
        source["examples"] = [
            example
            for example in source["examples"]
            if example.get("sqlDialect") == request.sqlDialect
        ]
        if offline and not source["examples"]:
            raise Controlled("UNSUPPORTED_QUESTION")
        if len(json.dumps(source, ensure_ascii=False).encode("utf-8")) > budget:
            raise Controlled("INSUFFICIENT_CONTEXT")
        return source


async def generate_with_context(data: Input, execution_mode: str) -> Candidate:
    if not data.semanticContext.tables:
        raise Controlled("INSUFFICIENT_CONTEXT")
    if execution_mode == "API" and not os.getenv("LLM_API_KEY", "").strip():
        raise Controlled("PROVIDER_UNAVAILABLE", 503)
    if execution_mode == "AUTO" and not os.getenv("LLM_API_KEY", "").strip():
        execution_mode = "DETERMINISTIC"
    if execution_mode != "DETERMINISTIC":
        raise Controlled("PROVIDER_UNAVAILABLE", 503)
    context = SemanticContextSelector().select(data, offline=True)
    client: TextToSqlModelClient = DeterministicModelClient()
    return validate(await client.generate(data, context), context, data.sqlDialect.value)


@app.post("/internal/v1/text-to-sql", response_model=Candidate, response_model_exclude_none=True)
async def generate(
    req: Request,
    authorization: str = Header(default=""),
    x_execution_mode: str = Header(default="AUTO"),
):
    verify_service_token(authorization)
    if x_execution_mode not in {"AUTO", "API", "DETERMINISTIC"}:
        raise Controlled("INVALID_REQUEST", 400)
    body = await req.body()
    if len(body) > 1048576:
        raise Controlled("INVALID_REQUEST", 413)
    try:
        decoded = json.loads(body)
    except (UnicodeDecodeError, ValueError):
        raise Controlled("INVALID_REQUEST", 422)
    if isinstance(decoded, dict) and decoded.get("sqlDialect") not in {
        dialect.value for dialect in Dialect
    }:
        raise Controlled("SQL_DIALECT_ERROR", 422)
    try:
        data = Input.model_validate(decoded)
    except ValidationError:
        raise Controlled("INVALID_REQUEST", 422)
    if req.headers.get("x-correlation-id") != data.correlationId:
        raise Controlled("INVALID_REQUEST", 400)
    return await generate_with_context(data, x_execution_mode)
