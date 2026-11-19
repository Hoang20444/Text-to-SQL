# Wren Memory source notice

The files in `ai-service/wren/memory/` are selected source modules from the
Wren Engine project, Copyright 2024 Canner, Inc., distributed under Apache-2.0.
The original upstream license is retained in `WREN-APACHE-2.0.txt`.

These files are imported by `ai-service/knowledge.py` and provide the actual
LanceDB-backed schema and approved-query memory. They are not a copy of the full
Wren application or planning engine. The project adds a local Ollama embedding
adapter and dataset-scoped lifecycle in `knowledge.py`.
