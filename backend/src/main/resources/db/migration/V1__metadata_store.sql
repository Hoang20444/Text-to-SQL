-- Metadata store: registered warehouses and the datasets exposed on top of them.
-- Credentials are never stored here; credential_reference only names a server-side secret.

CREATE TABLE warehouses (
    id                   VARCHAR(100) PRIMARY KEY,
    name                 VARCHAR(200) NOT NULL,
    engine               VARCHAR(20)  NOT NULL
        CHECK (engine IN ('POSTGRESQL', 'MYSQL', 'SQL_SERVER')),
    status               VARCHAR(20)  NOT NULL
        CHECK (status IN ('ACTIVE', 'UNAVAILABLE', 'INVALID', 'DISABLED')),
    host                 VARCHAR(255) NOT NULL,
    port                 INTEGER      NOT NULL CHECK (port BETWEEN 1 AND 65535),
    database_name        VARCHAR(128) NOT NULL,
    default_schema       VARCHAR(128),
    username             VARCHAR(128) NOT NULL,
    credential_reference VARCHAR(256) NOT NULL CHECK (credential_reference LIKE 'env:%'),
    read_only_query      BOOLEAN      NOT NULL DEFAULT TRUE,
    metadata_discovery   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX warehouses_created_at_idx ON warehouses (created_at DESC, id);

CREATE TABLE datasets (
    id               VARCHAR(100) PRIMARY KEY,
    warehouse_id     VARCHAR(100) NOT NULL REFERENCES warehouses (id),
    name             VARCHAR(200) NOT NULL,
    description      TEXT         NOT NULL DEFAULT '',
    status           VARCHAR(20)  NOT NULL
        CHECK (status IN ('ACTIVE', 'INVALID_SCHEMA', 'DISABLED')),
    allowed_schemas  JSONB        NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(allowed_schemas) = 'array'),
    allowed_tables   JSONB        NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(allowed_tables) = 'array'),
    semantic_version VARCHAR(64)  NOT NULL,
    schema_version   VARCHAR(64)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX datasets_warehouse_idx ON datasets (warehouse_id);
CREATE INDEX datasets_created_at_idx ON datasets (created_at DESC, id);
