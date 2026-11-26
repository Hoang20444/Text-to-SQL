-- Approved question/SQL examples of the dataset knowledge base (T-4.1.2).
-- Java is the source of truth: the Python vector index drops approvals whenever the
-- semantic model changes, so every reindex re-validates and re-sends these rows.
CREATE TABLE knowledge_example (
 id varchar(100) PRIMARY KEY,
 dataset_id varchar(100) NOT NULL REFERENCES dataset(id) ON DELETE CASCADE,
 document jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX knowledge_example_dataset ON knowledge_example(dataset_id, created_at);
