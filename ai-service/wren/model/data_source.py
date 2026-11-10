"""SQL dialect identifiers required by Wren's semantic SQL rewriter.

This module deliberately retains only the three engines supported by this
application. Wren connector implementations and connection models are outside
the planning runtime.
"""

from enum import StrEnum


class DataSource(StrEnum):
    mssql = "mssql"
    mysql = "mysql"
    postgres = "postgres"
