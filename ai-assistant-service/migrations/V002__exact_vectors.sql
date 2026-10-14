CREATE EXTENSION IF NOT EXISTS vector;
ALTER TABLE policy_chunks ADD COLUMN embedding vector(1536), ADD COLUMN embedding_model text;
ALTER TABLE policy_chunks ADD CONSTRAINT embedding_pair CHECK ((embedding IS NULL)=(embedding_model IS NULL));
ALTER TABLE policy_chunks ADD CONSTRAINT embedding_pin CHECK (embedding_model IS NULL OR embedding_model='text-embedding-3-small');
-- Exact scans are intentional for the small corpus. No approximate index/recall claim.
