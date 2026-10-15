-- Disabled verification core. No legacy identity, mapping or verification backfill.
CREATE TABLE verification_control (
    id integer PRIMARY KEY CHECK (id = 1)
);
INSERT INTO verification_control(id) VALUES (1);
CREATE TABLE verification_enrollments (
    id uuid PRIMARY KEY,
    issuer varchar(512) NOT NULL,
    subject varchar(255) NOT NULL,
    binding_digest bytea NOT NULL,
    binding_key_id varchar(32) NOT NULL,
    phone varchar(13) NOT NULL CHECK (phone ~ '^\+9715[024568][0-9]{7}$'),
    email varchar(254) NOT NULL,
    contact_version integer NOT NULL DEFAULT 1 CHECK (contact_version > 0),
    state varchar(32) NOT NULL CHECK (state IN ('PENDING_CONTACTS','VERIFIED','BLOCKED_MIGRATION')),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    UNIQUE(issuer, subject)
);
CREATE TABLE verification_phone_claims (
    phone varchar(13) PRIMARY KEY CHECK (phone ~ '^\+9715[024568][0-9]{7}$'),
    enrollment_id uuid NOT NULL UNIQUE REFERENCES verification_enrollments(id),
    state varchar(16) NOT NULL DEFAULT 'RESERVED' CHECK (state = 'RESERVED')
);
CREATE TABLE verification_challenges (
    id uuid PRIMARY KEY,
    enrollment_id uuid NOT NULL REFERENCES verification_enrollments(id),
    purpose varchar(32) NOT NULL CHECK (purpose IN ('REGISTRATION_EMAIL','REGISTRATION_PHONE')),
    contact_version integer NOT NULL CHECK (contact_version > 0),
    state varchar(24) NOT NULL CHECK (state IN ('PENDING_DELIVERY','OPEN','CONSUMED','EXPIRED','SUPERSEDED','LOCKED','DELIVERY_FAILED')),
    digest bytea,
    digest_key_id varchar(32) NOT NULL,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    resend_at timestamptz NOT NULL
);
CREATE INDEX verification_challenge_budget ON verification_challenges(enrollment_id, purpose, created_at);
CREATE INDEX verification_challenge_global_budget ON verification_challenges(created_at);
CREATE UNIQUE INDEX verification_one_current_challenge ON verification_challenges(enrollment_id, purpose)
    WHERE state IN ('PENDING_DELIVERY','OPEN');
CREATE TABLE verification_receipts (
    id uuid PRIMARY KEY,
    enrollment_id uuid NOT NULL REFERENCES verification_enrollments(id),
    challenge_id uuid NOT NULL UNIQUE REFERENCES verification_challenges(id),
    purpose varchar(32) NOT NULL,
    contact_version integer NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE(enrollment_id, purpose, contact_version)
);
CREATE TABLE verification_delivery_outbox (
    id uuid PRIMARY KEY,
    challenge_id uuid NOT NULL UNIQUE REFERENCES verification_challenges(id),
    enrollment_id uuid NOT NULL REFERENCES verification_enrollments(id),
    purpose varchar(32) NOT NULL,
    contact_version integer NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('PENDING','LEASED','ACCEPTED','RETRY_WAIT','EXPIRED','FAILED')),
    key_id varchar(32) NOT NULL,
    nonce bytea,
    ciphertext bytea,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    fence uuid,
    available_at timestamptz NOT NULL,
    lease_until timestamptz,
    expires_at timestamptz NOT NULL,
    CHECK ((nonce IS NULL) = (ciphertext IS NULL)),
    CHECK (state NOT IN ('ACCEPTED','EXPIRED','FAILED') OR ciphertext IS NULL)
);
CREATE INDEX verification_delivery_due ON verification_delivery_outbox(state, available_at);
CREATE TABLE verification_commands (
    requester_digest bytea NOT NULL,
    command_id uuid NOT NULL,
    hash_key_id varchar(32) NOT NULL,
    payload_hash bytea NOT NULL,
    result varchar(512) NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY(requester_digest, command_id)
);
