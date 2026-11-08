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
