-- Disabled login proof transport/storage. No identity, receipt, epoch or verified-state backfill.
CREATE TABLE sso_login_proof_control (id integer PRIMARY KEY CHECK (id = 1));
INSERT INTO sso_login_proof_control(id) VALUES (1);
CREATE TABLE sso_login_proof_admission (
    writer_hash bytea PRIMARY KEY CHECK (octet_length(writer_hash) = 32),
    credits numeric(20,6) NOT NULL CHECK (credits BETWEEN 0 AND 20),
    refilled_at timestamptz NOT NULL
);
CREATE TABLE sso_login_events (
    issuer varchar(512) NOT NULL,
    event_id uuid NOT NULL,
    binding_hash bytea NOT NULL CHECK (octet_length(binding_hash) = 32),
    authentication_hash bytea NOT NULL CHECK (octet_length(authentication_hash) = 32),
    identity_id uuid REFERENCES identities(id),
    identity_digest bytea NOT NULL CHECK (octet_length(identity_digest) = 32),
    password_authenticated_at timestamptz NOT NULL,
    verified_email varchar(254),
    recipient_digest bytea NOT NULL CHECK (octet_length(recipient_digest) = 32),
    email_contact_version integer NOT NULL CHECK (email_contact_version > 0),
    phone_contact_version integer NOT NULL CHECK (phone_contact_version > 0),
    verification_epoch bigint NOT NULL CHECK (verification_epoch > 0),
    projection_revision integer NOT NULL CHECK (projection_revision > 0),
    recipient_expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    retained_until timestamptz NOT NULL,
    PRIMARY KEY (issuer, event_id),
    CHECK (recipient_expires_at = created_at + interval '24 hours'),
    CHECK (retained_until = created_at + interval '604800 seconds')
);
CREATE INDEX sso_login_event_erasure ON sso_login_events(recipient_expires_at) WHERE verified_email IS NOT NULL;
CREATE TABLE sso_login_proofs (
    id uuid PRIMARY KEY,
    issuer varchar(512) NOT NULL,
    code_digest varchar(64) NOT NULL CHECK (code_digest ~ '^[0-9a-f]{64}$'),
    code_key_id varchar(32) NOT NULL CHECK (code_key_id ~ '^[A-Za-z0-9_-]{1,32}$'),
    binding_hash bytea NOT NULL CHECK (octet_length(binding_hash) = 32),
    payload_hash bytea NOT NULL CHECK (octet_length(payload_hash) = 32),
    kind varchar(16) NOT NULL CHECK (kind IN ('interactive','reuse')),
    event_id uuid,
    login_notice_eligible boolean NOT NULL,
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    retained_until timestamptz NOT NULL,
    UNIQUE (issuer, code_digest),
    FOREIGN KEY (issuer, event_id) REFERENCES sso_login_events(issuer, event_id),
    CHECK ((kind = 'interactive' AND event_id IS NOT NULL AND login_notice_eligible)
        OR (kind = 'reuse' AND event_id IS NULL AND NOT login_notice_eligible)),
    CHECK (issued_at < expires_at AND expires_at <= issued_at + interval '120 seconds'),
    CHECK (retained_until = created_at + interval '604800 seconds')
);
CREATE INDEX sso_login_proof_hourly ON sso_login_proofs(created_at);
CREATE TABLE sso_login_proof_commands (
    requester_hash bytea NOT NULL CHECK (octet_length(requester_hash) = 32),
    command_id uuid NOT NULL,
    payload_hash bytea NOT NULL CHECK (octet_length(payload_hash) = 32),
    proof_id uuid NOT NULL REFERENCES sso_login_proofs(id),
    created_at timestamptz NOT NULL,
    retained_until timestamptz NOT NULL,
    PRIMARY KEY (requester_hash, command_id),
    CHECK (retained_until = created_at + interval '604800 seconds')
);
