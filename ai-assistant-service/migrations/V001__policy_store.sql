CREATE TABLE IF NOT EXISTS assistant_migrations(version integer PRIMARY KEY, digest text NOT NULL);
CREATE TABLE policy_documents (
 document_id varchar(128) NOT NULL, version varchar(64) NOT NULL, digest char(64) NOT NULL,
 content text NOT NULL CHECK(octet_length(content)<=1048576), provenance text NOT NULL,
 effective_from timestamptz NOT NULL, effective_to timestamptz,
 supersedes varchar(64), index_version varchar(64) NOT NULL,
 PRIMARY KEY(document_id,version), CHECK(effective_to IS NULL OR effective_to>effective_from)
);
CREATE TABLE policy_chunks (
 document_id varchar(128) NOT NULL, version varchar(64) NOT NULL, ordinal integer NOT NULL,
 section varchar(256) NOT NULL, content text NOT NULL CHECK(length(content)<=1000),
 search tsvector GENERATED ALWAYS AS(to_tsvector('simple',content)) STORED,
 PRIMARY KEY(document_id,version,ordinal), FOREIGN KEY(document_id,version) REFERENCES policy_documents
);
CREATE INDEX policy_search ON policy_chunks USING gin(search);
CREATE TABLE active_index(singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),index_version varchar(64) NOT NULL);
-- Provision logins/passwords outside source control. Runtime gets no migration/ingestion credential.
DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='assistant_reader') THEN CREATE ROLE assistant_reader NOLOGIN; END IF; END $$;
GRANT USAGE ON SCHEMA public TO assistant_reader;
GRANT SELECT ON policy_documents,policy_chunks,active_index TO assistant_reader;
