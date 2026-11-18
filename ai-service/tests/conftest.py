"""Ensure ai-service runtime modules and sibling test helpers are importable."""

import sys
from pathlib import Path

AI_SERVICE_DIR = str(Path(__file__).resolve().parents[1])
TESTS_DIR = str(Path(__file__).resolve().parent)

for path in (AI_SERVICE_DIR, TESTS_DIR):
    if path not in sys.path:
        sys.path.insert(0, path)
