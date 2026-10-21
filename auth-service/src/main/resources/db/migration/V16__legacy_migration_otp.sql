-- Initial migration evidence only. No recovery generation, decision, publication or final authority.
CREATE TABLE legacy_migration_otp_stages (
 migration_id uuid PRIMARY KEY REFERENCES legacy_migration_intents(id),evidence_generation_id uuid NOT NULL UNIQUE,
 stage_operation_id uuid NOT NULL UNIQUE,command_id uuid NOT NULL,decision_command_id uuid NOT NULL UNIQUE,
 retention_key_id varchar(32) NOT NULL,target_binding_hash text NOT NULL CHECK(target_binding_hash~'^[0-9a-f]{64}$'),email_digest text NOT NULL CHECK(email_digest~'^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL,intent_deadline timestamptz NOT NULL,CHECK(intent_deadline>created_at AND intent_deadline<=created_at+interval '15 minutes')
);
CREATE TABLE legacy_migration_otp_stage_acks (
 migration_id uuid PRIMARY KEY REFERENCES legacy_migration_otp_stages(migration_id),profile_id uuid NOT NULL,
 baseline_generation bigint NOT NULL CHECK(baseline_generation>0),contact_mutation_revision bigint NOT NULL CHECK(contact_mutation_revision>=0),
 projection_present boolean NOT NULL,projection_revision bigint,phone_contact_version bigint,verification_epoch bigint,
 stage_ack_hash text NOT NULL CHECK(stage_ack_hash~'^[0-9a-f]{64}$'),baseline_hash text NOT NULL CHECK(baseline_hash~'^[0-9a-f]{64}$'),
 payload_hash text NOT NULL CHECK(payload_hash~'^[0-9a-f]{64}$'),binding_hash text NOT NULL CHECK(binding_hash~'^[0-9a-f]{64}$'),ack_json text NOT NULL CHECK(octet_length(ack_json)<=12288),
 CHECK((projection_present AND projection_revision IS NOT NULL AND phone_contact_version IS NOT NULL AND verification_epoch IS NOT NULL AND projection_revision>0 AND phone_contact_version>0 AND verification_epoch>0) OR
  (NOT projection_present AND projection_revision IS NULL AND phone_contact_version IS NULL AND verification_epoch IS NULL))
);
CREATE TABLE legacy_migration_otp_candidates (
 id uuid PRIMARY KEY,migration_id uuid NOT NULL REFERENCES legacy_migration_otp_stage_acks(migration_id),purpose varchar(5) NOT NULL CHECK(purpose IN ('EMAIL','PHONE')),
 receipt_generation bigint NOT NULL DEFAULT 1 CHECK(receipt_generation=1),contact_digest text NOT NULL CHECK(contact_digest~'^[0-9a-f]{64}$'),UNIQUE(migration_id,purpose)
);
CREATE TABLE legacy_migration_otp_challenges (
 id uuid PRIMARY KEY,migration_id uuid NOT NULL REFERENCES legacy_migration_otp_stages(migration_id),candidate_id uuid NOT NULL REFERENCES legacy_migration_otp_candidates(id),
 identity_id uuid NOT NULL REFERENCES identities(id),purpose varchar(5) NOT NULL CHECK(purpose IN ('EMAIL','PHONE')),
 binding_hash text NOT NULL,key_id varchar(32) NOT NULL,digest bytea CHECK(digest IS NULL OR octet_length(digest)=32),
 state varchar(24) NOT NULL CHECK(state IN ('PENDING_DELIVERY','OPEN','CONSUMED','LOCKED','SUPERSEDED','EXPIRED','DELIVERY_FAILED')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),created_at timestamptz NOT NULL,expires_at timestamptz NOT NULL,resend_at timestamptz NOT NULL,
 CHECK(created_at<expires_at AND expires_at<=created_at+interval '5 minutes'),CHECK(resend_at=created_at+interval '60 seconds')
);
CREATE TABLE legacy_migration_otp_receipts (
 id uuid PRIMARY KEY,migration_id uuid NOT NULL REFERENCES legacy_migration_otp_stages(migration_id),challenge_id uuid NOT NULL UNIQUE REFERENCES legacy_migration_otp_challenges(id),
 candidate_id uuid NOT NULL REFERENCES legacy_migration_otp_candidates(id),purpose varchar(5) NOT NULL CHECK(purpose IN ('EMAIL','PHONE')),
 receipt_generation bigint NOT NULL CHECK(receipt_generation=1),binding_hash text NOT NULL,created_at timestamptz NOT NULL,UNIQUE(migration_id,purpose)
);
CREATE TABLE legacy_migration_otp_outbox (
 id uuid PRIMARY KEY,challenge_id uuid NOT NULL UNIQUE REFERENCES legacy_migration_otp_challenges(id),migration_id uuid NOT NULL REFERENCES legacy_migration_otp_stages(migration_id),
 key_id varchar(32) NOT NULL,nonce bytea CHECK(nonce IS NULL OR octet_length(nonce)=12),ciphertext bytea CHECK(ciphertext IS NULL OR octet_length(ciphertext)<=1024),
 state varchar(16) NOT NULL CHECK(state IN ('PENDING','LEASED','RETRY_WAIT','ACCEPTED','FAILED','EXPIRED')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 3),fence uuid,lease_until timestamptz,available_at timestamptz NOT NULL,expires_at timestamptz NOT NULL,
 UNIQUE(key_id,nonce),CHECK(state NOT IN ('ACCEPTED','FAILED','EXPIRED') OR ciphertext IS NULL)
);
CREATE TABLE legacy_migration_otp_commands (
 migration_id uuid NOT NULL REFERENCES legacy_migration_otp_stages(migration_id),writer varchar(64) NOT NULL,command_id uuid NOT NULL,
 key_id varchar(32) NOT NULL,payload_hash bytea NOT NULL CHECK(octet_length(payload_hash)=32),result_json text NOT NULL CHECK(octet_length(result_json)<=4096),
 created_at timestamptz NOT NULL,PRIMARY KEY(migration_id,writer,command_id)
);
CREATE TABLE auth_otp_issuance_reservations (
 id uuid PRIMARY KEY,kind varchar(12) NOT NULL CHECK(kind IN ('REGISTRATION','MIGRATION')),created_at timestamptz NOT NULL
);
CREATE INDEX auth_otp_issuance_hour ON auth_otp_issuance_reservations(created_at,id);
CREATE INDEX legacy_migration_otp_owner_budget ON legacy_migration_otp_challenges(identity_id,purpose,created_at);
CREATE INDEX legacy_migration_otp_delivery_claim ON legacy_migration_otp_outbox(available_at,id);
CREATE TRIGGER legacy_migration_otp_stage_immutable BEFORE UPDATE OR DELETE ON legacy_migration_otp_stages FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_otp_ack_immutable BEFORE UPDATE OR DELETE ON legacy_migration_otp_stage_acks FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_otp_candidate_immutable BEFORE UPDATE OR DELETE ON legacy_migration_otp_candidates FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_otp_receipt_immutable BEFORE UPDATE OR DELETE ON legacy_migration_otp_receipts FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_otp_command_immutable BEFORE UPDATE OR DELETE ON legacy_migration_otp_commands FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER auth_otp_issuance_immutable BEFORE UPDATE OR DELETE ON auth_otp_issuance_reservations FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
