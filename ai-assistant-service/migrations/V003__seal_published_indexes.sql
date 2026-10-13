CREATE TABLE published_indexes (
 index_version varchar(64) PRIMARY KEY,
 published_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO published_indexes(index_version)
 SELECT index_version FROM active_index ON CONFLICT DO NOTHING;
-- Operator-only seal. Runtime needs no write privileges and still reads active_index.
