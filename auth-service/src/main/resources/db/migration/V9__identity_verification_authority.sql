-- Safe-off authority staging only. No identities/external mappings/receipts are backfilled.
CREATE TABLE verification_authority_config(id integer PRIMARY KEY CHECK(id=1), retention_key_fingerprint bytea CHECK(octet_length(retention_key_fingerprint)=32));
INSERT INTO verification_authority_config(id) VALUES(1);
CREATE TABLE verification_authorities(
    enrollment_id uuid PRIMARY KEY REFERENCES verification_enrollments(id),
    pending_identity_id uuid NOT NULL UNIQUE,
    mapped_identity_id uuid UNIQUE REFERENCES identities(id),
    state varchar(32) NOT NULL CHECK(state IN ('PENDING_CONTACTS','PROJECTION_PENDING','PROJECTED','SUSPENDED','BLOCKED_MIGRATION')),
    revision integer NOT NULL DEFAULT 1 CHECK(revision>0),
    verification_epoch bigint NOT NULL DEFAULT 1 CHECK(verification_epoch>0),
    projection_revision integer NOT NULL DEFAULT 0 CHECK(projection_revision>=0),
    projection_operation_id uuid,
    projection_command_id uuid,
    projection_flow_digest bytea CHECK(octet_length(projection_flow_digest)=32),
    projection_writer varchar(64),
    email_candidate_id uuid,
    phone_candidate_id uuid,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CHECK(expires_at=created_at+interval '86400 seconds'),
    CHECK(mapped_identity_id IS NULL OR mapped_identity_id=pending_identity_id),
    CHECK(state<>'PROJECTED' OR (mapped_identity_id IS NOT NULL AND projection_operation_id IS NOT NULL AND email_candidate_id IS NOT NULL AND phone_candidate_id IS NOT NULL))
);
CREATE TABLE verification_candidates(
    id uuid PRIMARY KEY,
    enrollment_id uuid NOT NULL REFERENCES verification_enrollments(id),
    purpose varchar(32) NOT NULL CHECK(purpose IN ('REGISTRATION_EMAIL','REGISTRATION_PHONE')),
    version integer NOT NULL CHECK(version>0),
    canonical_value varchar(254) NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE(enrollment_id,purpose,version),
    UNIQUE(id,enrollment_id,purpose,version)
);
ALTER TABLE verification_authorities ADD FOREIGN KEY(email_candidate_id) REFERENCES verification_candidates(id);
ALTER TABLE verification_authorities ADD FOREIGN KEY(phone_candidate_id) REFERENCES verification_candidates(id);
ALTER TABLE verification_challenges ADD COLUMN candidate_id uuid REFERENCES verification_candidates(id);
ALTER TABLE verification_receipts ADD COLUMN candidate_id uuid REFERENCES verification_candidates(id);
ALTER TABLE verification_challenges ADD FOREIGN KEY(candidate_id,enrollment_id,purpose,contact_version) REFERENCES verification_candidates(id,enrollment_id,purpose,version);
ALTER TABLE verification_receipts ADD FOREIGN KEY(candidate_id,enrollment_id,purpose,contact_version) REFERENCES verification_candidates(id,enrollment_id,purpose,version);
ALTER TABLE verification_challenges ADD UNIQUE(id,candidate_id,enrollment_id,purpose,contact_version);
ALTER TABLE verification_receipts ADD FOREIGN KEY(challenge_id,candidate_id,enrollment_id,purpose,contact_version) REFERENCES verification_challenges(id,candidate_id,enrollment_id,purpose,contact_version);
CREATE UNIQUE INDEX verification_candidate_receipt ON verification_receipts(candidate_id) WHERE candidate_id IS NOT NULL;
-- Keep all previous reservations when a candidate is replaced. V6's one-claim-per-enrollment constraint stays intact.
CREATE TABLE verification_authority_phone_claims(
    phone varchar(13) PRIMARY KEY CHECK(phone ~ '^\+9715[024568][0-9]{7}$'),
    enrollment_id uuid NOT NULL REFERENCES verification_enrollments(id)
);
CREATE TABLE verification_flow_barriers(
    context_fingerprint bytea PRIMARY KEY CHECK(octet_length(context_fingerprint)=32),
    tuple_digest bytea NOT NULL UNIQUE CHECK(octet_length(tuple_digest)=32),
    subject_digest bytea NOT NULL CHECK(octet_length(subject_digest)=32),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK(expires_at>created_at)
);
CREATE INDEX verification_flow_hourly ON verification_flow_barriers(subject_digest,created_at);
CREATE TABLE verification_flows(
    context_fingerprint bytea PRIMARY KEY REFERENCES verification_flow_barriers(context_fingerprint),
    enrollment_id uuid NOT NULL REFERENCES verification_enrollments(id),
    tuple_digest bytea NOT NULL CHECK(octet_length(tuple_digest)=32),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK(expires_at>created_at)
);
CREATE INDEX verification_live_flows ON verification_flows(enrollment_id,expires_at);
CREATE TABLE verification_authority_admission(
    writer_digest bytea PRIMARY KEY CHECK(octet_length(writer_digest)=32),
    credits numeric(20,6) NOT NULL CHECK(credits BETWEEN 0 AND 20),
    refilled_at timestamptz NOT NULL
);
CREATE TABLE verification_authority_commands(
    requester_digest bytea NOT NULL CHECK(octet_length(requester_digest)=32),
    command_id uuid NOT NULL,
    flow_digest bytea NOT NULL CHECK(octet_length(flow_digest)=32),
    payload_hash bytea NOT NULL CHECK(octet_length(payload_hash)=32),
    response varchar(1024),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY(requester_digest,command_id),
    CHECK(expires_at=created_at+interval '604800 seconds')
);
-- Immutable candidates and permanent denial/replay keys cannot be rewritten or ordinarily deleted.
CREATE FUNCTION verification_candidate_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'immutable verification candidate' USING ERRCODE='23514'; END $$;
CREATE TRIGGER verification_candidate_no_update BEFORE UPDATE OR DELETE ON verification_candidates FOR EACH ROW EXECUTE FUNCTION verification_candidate_immutable();
CREATE FUNCTION verification_barrier_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'immutable verification barrier' USING ERRCODE='23514'; END $$;
CREATE TRIGGER verification_flow_barrier_no_change BEFORE UPDATE OR DELETE ON verification_flow_barriers FOR EACH ROW EXECUTE FUNCTION verification_barrier_immutable();
