"""Slimmed Wren semantic SQL planning package for ai-service."""

from wren.engine import WrenEngine
from wren.model.data_source import DataSource
from wren.model.error import WrenError

__version__ = "0.4.0"

__all__ = ["WrenEngine", "DataSource", "WrenError", "__version__"]
