# Work log

Times use UTC+07 (Asia/Ho_Chi_Minh). Entries describe committed work and its evidence.

| Date | Branch | Commit subject | Role / scope | Status and checks | Remaining |
|---|---|---|---|---|---|
| 2026-11-19 | `module/sprint-03-04-backend` | `feat(ai-data): add semantic compiler and dataset knowledge runtime` | AI & Data: cross-dialect semantic SQL compilation, validated draft assistance, dataset-scoped Wren Memory/LanceDB retrieval, approved examples, and Sprint 3 data verification. | Implemented AI/data paths with application callers; `pytest -q ai-service`: 154 passed; Sprint 1, 2, and 3 verification scripts passed. Wren/LanceDB exercised with deterministic test embeddings. | Live Ollama embeddings and Docker-backed warehouse integration were not run in this environment. Backend endpoints and Java orchestration are recorded in following entries. |
