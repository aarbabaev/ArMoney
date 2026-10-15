-- Disabled auth-owned tracker only. No identity/contact/epoch backfill or eligibility activation.
CREATE TABLE auth_verification_projection_control (
    id integer PRIMARY KEY CHECK (id = 1),
    retention_key_fingerprint bytea CHECK (octet_length(retention_key_fingerprint) = 32)
);
INSERT INTO auth_verification_projection_control(id) VALUES (1);
CREATE TABLE auth_verification_projection_admission (
    writer_hash bytea PRIMARY KEY CHECK (octet_length(writer_hash) = 32),
    credits numeric(20,6) NOT NULL CHECK (credits BETWEEN 0 AND 20),
    refilled_at timestamptz NOT NULL
);
CREATE TABLE auth_verification_projection_tombstones (
    writer_hash bytea NOT NULL CHECK (octet_length(writer_hash) = 32),
    operation_id uuid NOT NULL,
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    identity_digest bytea NOT NULL CHECK (octet_length(identity_digest) = 32),
    version integer NOT NULL CHECK (version > 0),
    phone_contact_version integer NOT NULL CHECK (phone_contact_version > 0),
    verification_epoch bigint NOT NULL CHECK (verification_epoch > 0),
    state varchar(32) NOT NULL CHECK (state IN ('PENDING','ACKNOWLEDGED','SUPERSEDED','CONFLICT','DISABLED_BAD_REQUEST','DISABLED_UNAUTHORIZED','EXPIRED_REPLAY','EXPIRED_UNRESOLVED')),
    recovery_barrier boolean NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (writer_hash, operation_id),
    CHECK (expires_at = created_at + interval '604800 seconds')
);
CREATE UNIQUE INDEX auth_projection_one_unresolved_identity ON auth_verification_projection_tombstones(identity_digest) WHERE recovery_barrier;
CREATE TABLE auth_verification_projection_commands (
    writer_hash bytea NOT NULL,
    operation_id uuid NOT NULL,
    identity_id uuid NOT NULL REFERENCES identities(id),
    enrollment_id uuid NOT NULL REFERENCES verification_enrollments(id),
    expected_version integer NOT NULL CHECK (expected_version BETWEEN 0 AND 2147483646),
    version integer NOT NULL CHECK (version = expected_version + 1),
    canonical_phone varchar(13) NOT NULL CHECK (canonical_phone ~ '^\+9715[024568][0-9]{7}$'),
    phone_contact_version integer NOT NULL CHECK (phone_contact_version > 0),
    email_contact_version integer NOT NULL CHECK (email_contact_version > 0),
    verification_epoch bigint NOT NULL CHECK (verification_epoch > 0),
    email_receipt_id uuid NOT NULL,
    phone_receipt_id uuid NOT NULL,
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    request_body bytea NOT NULL CHECK (octet_length(request_body) <= 12288),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    fence uuid,
    lease_until timestamptz,
    available_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (writer_hash, operation_id),
    UNIQUE (identity_id, version),
    FOREIGN KEY (writer_hash, operation_id) REFERENCES auth_verification_projection_tombstones(writer_hash, operation_id),
    CHECK ((fence IS NULL) = (lease_until IS NULL)),
    CHECK (expires_at = created_at + interval '604800 seconds')
);
CREATE INDEX auth_projection_due ON auth_verification_projection_commands(available_at);
CREATE INDEX auth_projection_purge ON auth_verification_projection_commands(expires_at);
