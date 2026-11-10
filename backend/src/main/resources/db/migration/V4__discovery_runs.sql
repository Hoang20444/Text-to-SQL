-- Physical schema discovery (T-2.1.1): one row per scan of a warehouse. The metadata snapshot
-- holds schemas, tables, columns, primary keys and foreign keys; never credentials.
CREATE TABLE discovery_runs (
    id             VARCHAR(100) PRIMARY KEY,
    warehouse_id   VARCHAR(100) NOT NULL REFERENCES warehouses (id) ON DELETE CASCADE,
    requested_by   VARCHAR(255) NOT NULL,
    status         VARCHAR(20)  NOT NULL
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED')),
    schema_version VARCHAR(100),
    table_count    INTEGER CHECK (table_count >= 0),
    metadata       JSONB CHECK (metadata IS NULL OR jsonb_typeof(metadata) = 'object'),
    error_code     VARCHAR(60),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ
);

CREATE INDEX discovery_runs_warehouse_idx ON discovery_runs (warehouse_id, created_at DESC);

-- At most one scan in progress per warehouse.
CREATE UNIQUE INDEX discovery_runs_one_active_idx
    ON discovery_runs (warehouse_id) WHERE status IN ('PENDING', 'RUNNING');

-- Latest successful scan, for the catalog and drift checks.
ALTER TABLE warehouses
    ADD COLUMN schema_version     VARCHAR(100),
    ADD COLUMN last_discovery_id  VARCHAR(100),
    ADD COLUMN last_discovered_at TIMESTAMPTZ;
