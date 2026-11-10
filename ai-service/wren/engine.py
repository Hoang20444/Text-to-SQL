"""WrenEngine semantic SQL planner, adapted for planning-only service use.

Example usage:

    from wren.engine import WrenEngine
    from wren.model.data_source import DataSource

    engine = WrenEngine(
        manifest_str="<base64-encoded MDL JSON>",
        data_source=DataSource.postgres,
        connection_info={"host": "localhost", "port": 5432, ...},
    )

    # Plan only (no DB required)
    planned_sql = engine.dry_plan("SELECT * FROM orders")

"""

from __future__ import annotations

import base64
import json
from contextlib import AbstractContextManager
from typing import TYPE_CHECKING, Any, Callable

from sqlglot import exp, parse_one

from wren.config import WrenConfig
from wren.mdl import get_manifest_extractor, get_session_context, to_json_base64
from wren.mdl.cte_rewriter import CTERewriter, get_sqlglot_dialect
from wren.model.data_source import DataSource
from wren.model.error import DIALECT_SQL, ErrorCode, ErrorPhase, WrenError
from wren.policy import validate_sql_policy

if TYPE_CHECKING:
    from wren.nla_planning import DatasetPlan


class WrenEngine:
    """Thin facade over wren-core MDL processing and connector execution.

    Parameters
    ----------
    manifest_str:
        Base64-encoded MDL JSON string (as produced by ``wren_core.to_json_base64``).
    data_source:
        Target data source enum value.
    connection_info:
        Dict of connection parameters OR a typed ConnectionInfo object.
    function_path:
        Optional path to a CSV file of custom function definitions.
        Passed through to wren-core SessionContext.
    """

    def __init__(
        self,
        manifest_str: str,
        data_source: DataSource | str,
        connection_info: dict[str, Any] | object,
        function_path: str | None = None,
        *,
        fallback: bool = True,
        config: WrenConfig | None = None,
        session_context_override: Any | None = None,
        planning_only: bool = False,
    ):
        if isinstance(data_source, str):
            data_source = DataSource(data_source)

        self.manifest_str = manifest_str
        self.data_source = data_source
        self.function_path = function_path
        self._fallback = fallback
        self._config = config or WrenConfig()
        # Application integration: allow a bounded, leased native session.
        self._session_context_override = session_context_override
        self._planning_only = planning_only

        if isinstance(connection_info, dict) and connection_info:
            raise ValueError("The focused semantic planner does not accept warehouse credentials.")
        self.connection_info = connection_info


    # ------------------------------------------------------------------
    # SQL transformation (no DB access)
    # ------------------------------------------------------------------

    def dry_plan(self, sql: str, properties: dict | None = None) -> str:
        """Plan SQL through MDL and return the expanded SQL in the target dialect.

        Transformation flow::

            User SQL (target dialect, e.g. Postgres)
              → sqlglot parse (target dialect)
              → qualify_tables + normalize_identifiers + qualify_columns
              → identify referenced models and columns
              → per-model: wren-core transform_sql → Wren dialect SQL
              → per-model: sqlglot parse (Wren dialect) → inject as CTE
              → sqlglot generate (target dialect)
              → output SQL with model CTEs in target dialect
        """
        return self._plan(sql, properties)

    @classmethod
    def plan_for_dataset(
        cls,
        *,
        sql: str,
        manifest: dict[str, Any],
        data_source: str,
        session_lease: Callable[[str, str], AbstractContextManager],
    ) -> DatasetPlan:
        """NLA fork: plan authorized physical SQL and verify rewritten lineage.

        No connector is available in this path. Java remains responsible for
        principal/resource authorization and independent execution validation.
        """
        from wren.nla_planning import plan_for_dataset

        return plan_for_dataset(cls, sql, manifest, data_source, session_lease)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def close(self) -> None:
        """Release no resources; the focused facade owns no connector."""

    def _plan(self, sql: str, properties: dict | None) -> str:
        processed = None
        if properties:
            processed = frozenset(properties.items())

        try:
            # Extract minimal manifest scoped to tables referenced in the SQL.
            # Use sqlglot (not DataFusion parser) since input is target dialect.
            dialect = get_sqlglot_dialect(self.data_source)
            ast = parse_one(sql, dialect=dialect)

            # Policy validation: check tables and functions before execution.
            if self._config.strict_mode or self._config.denied_functions:
                manifest_json = json.loads(base64.b64decode(self.manifest_str))
                model_names = {m["name"] for m in manifest_json.get("models", [])}
                validate_sql_policy(ast, model_names, self._config)

            tables = [t.name for t in ast.find_all(exp.Table)]
            extractor = get_manifest_extractor(self.manifest_str)
            manifest = extractor.extract_by(tables)
            effective_manifest = to_json_base64(manifest)
        except WrenError:
            raise
        except Exception as e:
            if self._config.strict_mode or self._config.denied_functions:
                raise WrenError(
                    ErrorCode.INVALID_SQL,
                    str(e),
                    phase=ErrorPhase.SQL_PLANNING,
                    metadata={DIALECT_SQL: sql},
                ) from e
            effective_manifest = self.manifest_str

        try:
            session = self._session_context_override or get_session_context(
                effective_manifest,
                self.function_path,
                processed,
                self.data_source.name,
            )
            rewriter = CTERewriter(
                effective_manifest,
                session,
                self.data_source,
                fallback=self._fallback,
            )
            return rewriter.rewrite(sql)
        except Exception as e:
            raise WrenError(
                ErrorCode.INVALID_SQL,
                str(e),
                phase=ErrorPhase.SQL_PLANNING,
                metadata={DIALECT_SQL: sql},
            ) from e
