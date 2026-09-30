CREATE TABLE sessions (
    token_hash CHAR(64) PRIMARY KEY,
    identity_id UUID NOT NULL REFERENCES identities(id),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (expires_at > created_at)
);
CREATE INDEX sessions_expiry_idx ON sessions(expires_at);
CREATE INDEX sessions_identity_idx ON sessions(identity_id);

CREATE TABLE auth_attempts (
    subject_hash CHAR(64) PRIMARY KEY,
    window_start TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts > 0)
);
CREATE INDEX auth_attempts_window_idx ON auth_attempts(window_start);
