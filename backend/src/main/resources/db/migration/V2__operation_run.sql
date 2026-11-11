-- Background/admin operation runs (T-2.1.1: metadata discovery runs).
CREATE TABLE operation_run (
 id varchar(100) PRIMARY KEY,
 kind varchar(30) NOT NULL,
 document jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX operation_run_kind_created ON operation_run(kind, created_at DESC);
