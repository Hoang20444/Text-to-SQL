-- Semantic model snapshot per dataset (metrics, dimensions, relationships, examples, rules,
-- versions). Physical tables are read live from the warehouse when a question is asked.
ALTER TABLE datasets
    ADD COLUMN semantic_model JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(semantic_model) = 'object');

-- Basic query history (T-1.3.2). Result rows are not stored; secrets never are.
CREATE TABLE query_history (
    id               VARCHAR(100) PRIMARY KEY,
    correlation_id   VARCHAR(100) NOT NULL,
    owner_subject    VARCHAR(255) NOT NULL,
    warehouse_id     VARCHAR(100) NOT NULL REFERENCES warehouses (id),
    dataset_id       VARCHAR(100) NOT NULL REFERENCES datasets (id),
    engine           VARCHAR(20)  NOT NULL
        CHECK (engine IN ('POSTGRESQL', 'MYSQL', 'SQL_SERVER')),
    question         TEXT         NOT NULL,
    status           VARCHAR(20)  NOT NULL
        CHECK (status IN ('RUNNING', 'SUCCESS', 'REJECTED', 'VALIDATION_ERROR', 'QUERY_ERROR',
                          'TIMEOUT')),
    execution_mode   VARCHAR(20)  NOT NULL CHECK (execution_mode IN ('API', 'DETERMINISTIC')),
    model            VARCHAR(500),
    generated_sql    TEXT,
    row_count        INTEGER CHECK (row_count >= 0),
    truncated        BOOLEAN,
    latency_ms       INTEGER CHECK (latency_ms >= 0),
    error_code       VARCHAR(60),
    semantic_version VARCHAR(64)  NOT NULL,
    schema_version   VARCHAR(64)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ
);

CREATE INDEX query_history_created_idx ON query_history (created_at DESC, id);
CREATE INDEX query_history_owner_idx ON query_history (owner_subject, created_at DESC);
