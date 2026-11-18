"""DataSource enum without connector/database dependencies."""

from __future__ import annotations

from enum import StrEnum, auto


class DataSource(StrEnum):
    athena = auto()
    bigquery = auto()
    canner = auto()
    clickhouse = auto()
    datafusion = auto()
    mssql = auto()
    mysql = auto()
    doris = auto()
    oracle = auto()
    postgres = auto()
    redshift = auto()
    snowflake = auto()
    trino = auto()
    local_file = auto()
    s3_file = auto()
    minio_file = auto()
    gcs_file = auto()
    duckdb = auto()
    spark = auto()
    databricks = auto()
