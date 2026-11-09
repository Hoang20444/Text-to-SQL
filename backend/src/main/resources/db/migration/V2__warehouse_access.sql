-- Which OIDC subject (token "sub") may access which warehouse. ADMIN bypasses this table.

CREATE TABLE warehouse_access (
    subject      VARCHAR(255) NOT NULL,
    warehouse_id VARCHAR(100) NOT NULL REFERENCES warehouses (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (subject, warehouse_id)
);

CREATE INDEX warehouse_access_warehouse_idx ON warehouse_access (warehouse_id);
