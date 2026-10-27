-- Genuine managed-purpose proof custody only; no mapping, marker, session cutover or activation.
CREATE TABLE legacy_migration_managed_attempts (
 id uuid PRIMARY KEY,receipt_id uuid NOT NULL UNIQUE,migration_id uuid NOT NULL REFERENCES legacy_migration_intents(id),
 owner_id uuid NOT NULL REFERENCES identities(id),command_id uuid NOT NULL,retention_key_id varchar(32) NOT NULL,
 account_proof_mode varchar(24) NOT NULL CHECK(account_proof_mode IN ('LOCAL_PASSWORD','LEGACY_OIDC_REAUTH')),
 account_receipt_hash text NOT NULL CHECK(account_receipt_hash~'^[0-9a-f]{64}$'),
 owner_generation bigint NOT NULL CHECK(owner_generation>=0),mode_generation bigint NOT NULL CHECK(mode_generation>=0),email_generation bigint NOT NULL CHECK(email_generation>0),
 issuer text NOT NULL,client_id text NOT NULL,redirect_uri text NOT NULL,expected_subject text,legacy_issuer text,legacy_subject text,
 cookie_hash bytea NOT NULL CHECK(octet_length(cookie_hash)=32),state_hash bytea NOT NULL CHECK(octet_length(state_hash)=32),nonce_hash bytea NOT NULL CHECK(octet_length(nonce_hash)=32),
 pkce_challenge varchar(43) NOT NULL,custody_key_id varchar(32) NOT NULL,custody_nonce bytea NOT NULL CHECK(octet_length(custody_nonce)=12),custody_ciphertext bytea NOT NULL CHECK(octet_length(custody_ciphertext) BETWEEN 59 AND 256),
 created_at timestamptz NOT NULL,account_deadline timestamptz NOT NULL,attempt_deadline timestamptz NOT NULL,
 CHECK(created_at<attempt_deadline AND attempt_deadline<=account_deadline),
 CHECK((account_proof_mode='LOCAL_PASSWORD' AND expected_subject IS NULL AND legacy_issuer IS NULL AND legacy_subject IS NULL)
 OR (account_proof_mode='LEGACY_OIDC_REAUTH' AND expected_subject IS NOT NULL AND legacy_issuer IS NOT NULL AND legacy_subject IS NOT NULL AND legacy_issuer=issuer AND legacy_subject=expected_subject)),
 UNIQUE(migration_id),UNIQUE(owner_id,command_id),UNIQUE(custody_key_id,custody_nonce),UNIQUE(retention_key_id,cookie_hash)
);
CREATE TABLE legacy_migration_managed_claims (
 attempt_id uuid PRIMARY KEY REFERENCES legacy_migration_managed_attempts(id),invocation_id uuid NOT NULL UNIQUE,
 callback_hash bytea NOT NULL CHECK(octet_length(callback_hash)=32),started_at timestamptz NOT NULL,
 backend_pid integer NOT NULL,backend_start timestamptz NOT NULL,admission_txid bigint NOT NULL
);
CREATE TABLE legacy_migration_managed_receipts (
 receipt_id uuid PRIMARY KEY,attempt_id uuid NOT NULL UNIQUE REFERENCES legacy_migration_managed_attempts(id),migration_id uuid NOT NULL UNIQUE REFERENCES legacy_migration_intents(id),
 owner_id uuid NOT NULL REFERENCES identities(id),account_proof_mode varchar(24) NOT NULL CHECK(account_proof_mode IN ('LOCAL_PASSWORD','LEGACY_OIDC_REAUTH')),account_receipt_hash text NOT NULL,
 owner_generation bigint NOT NULL,mode_generation bigint NOT NULL,email_generation bigint NOT NULL,
 issuer text NOT NULL,subject text NOT NULL,client_id text NOT NULL,user_session_id text NOT NULL,
 authenticated_at timestamptz NOT NULL,issued_at timestamptz NOT NULL,token_expires_at timestamptz NOT NULL,
 account_deadline timestamptz NOT NULL,attempt_deadline timestamptz NOT NULL,created_at timestamptz NOT NULL,
 retention_key_id varchar(32) NOT NULL,target_binding_hash text NOT NULL CHECK(target_binding_hash~'^[0-9a-f]{64}$'),receipt_hash text NOT NULL CHECK(receipt_hash~'^[0-9a-f]{64}$'),
 CHECK(authenticated_at<=issued_at AND issued_at<token_expires_at AND created_at<account_deadline AND created_at<attempt_deadline)
);
CREATE TABLE legacy_migration_managed_targets (
 reservation_id uuid PRIMARY KEY,migration_id uuid NOT NULL UNIQUE REFERENCES legacy_migration_intents(id),owner_id uuid NOT NULL REFERENCES identities(id),
 receipt_id uuid NOT NULL UNIQUE REFERENCES legacy_migration_managed_receipts(receipt_id),issuer text NOT NULL,subject text NOT NULL,
 owner_generation bigint NOT NULL,mode_generation bigint NOT NULL,email_generation bigint NOT NULL,target_binding_hash text NOT NULL,
 created_at timestamptz NOT NULL,original_deadline timestamptz NOT NULL,CHECK(created_at<original_deadline),UNIQUE(issuer,subject)
);
CREATE TABLE legacy_migration_managed_completions (
 attempt_id uuid PRIMARY KEY REFERENCES legacy_migration_managed_attempts(id),receipt_id uuid NOT NULL UNIQUE REFERENCES legacy_migration_managed_receipts(receipt_id),completed_at timestamptz NOT NULL
);
CREATE INDEX legacy_migration_managed_callback_lookup ON legacy_migration_managed_attempts(retention_key_id,state_hash,cookie_hash);
CREATE TRIGGER legacy_migration_managed_attempt_immutable BEFORE UPDATE OR DELETE ON legacy_migration_managed_attempts FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_managed_claim_immutable BEFORE UPDATE OR DELETE ON legacy_migration_managed_claims FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_managed_receipt_immutable BEFORE UPDATE OR DELETE ON legacy_migration_managed_receipts FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_managed_target_immutable BEFORE UPDATE OR DELETE ON legacy_migration_managed_targets FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_managed_completion_immutable BEFORE UPDATE OR DELETE ON legacy_migration_managed_completions FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();

-- Current observation admission provenance only; no authority or receipt may be created from this alone.
CREATE TABLE legacy_migration_managed_observations (
 invocation_id uuid PRIMARY KEY,migration_id uuid NOT NULL REFERENCES legacy_migration_intents(id),receipt_id uuid NOT NULL REFERENCES legacy_migration_managed_receipts(receipt_id),
 reservation_id uuid NOT NULL REFERENCES legacy_migration_managed_targets(reservation_id),started_at timestamptz NOT NULL,backend_pid integer NOT NULL,backend_start timestamptz NOT NULL,admission_txid bigint NOT NULL
);
CREATE TRIGGER legacy_migration_managed_observation_immutable BEFORE UPDATE OR DELETE ON legacy_migration_managed_observations FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();

-- Genuine legacy OIDC account mode; published V15 remains byte-identical.
ALTER TABLE legacy_migration_intents DROP CONSTRAINT legacy_migration_intents_proof_mode_check;
ALTER TABLE legacy_migration_intents ADD CONSTRAINT legacy_migration_intents_proof_mode_check CHECK(proof_mode IN ('LOCAL_PASSWORD','LEGACY_OIDC_REAUTH'));
ALTER TABLE legacy_migration_intents ADD COLUMN account_receipt_id uuid;
ALTER TABLE legacy_migration_intents ADD COLUMN legacy_issuer text;
ALTER TABLE legacy_migration_intents ADD COLUMN legacy_subject text;
ALTER TABLE legacy_migration_intents ADD COLUMN association_generation bigint;
ALTER TABLE legacy_migration_intents ADD CONSTRAINT legacy_migration_mode_binding CHECK(
 (proof_mode='LOCAL_PASSWORD' AND legacy_issuer IS NULL AND legacy_subject IS NULL AND association_generation IS NULL)
 OR (proof_mode='LEGACY_OIDC_REAUTH' AND account_receipt_id IS NOT NULL AND legacy_issuer IS NOT NULL AND legacy_subject IS NOT NULL AND association_generation IS NOT NULL AND association_generation>=0));
CREATE TABLE legacy_migration_account_attempts (
 id uuid PRIMARY KEY,receipt_id uuid NOT NULL UNIQUE,command_id uuid NOT NULL UNIQUE,retention_key_id varchar(32) NOT NULL,
 issuer text NOT NULL,client_id text NOT NULL,redirect_uri text NOT NULL,
 cookie_hash bytea NOT NULL CHECK(octet_length(cookie_hash)=32),state_hash bytea NOT NULL CHECK(octet_length(state_hash)=32),nonce_hash bytea NOT NULL CHECK(octet_length(nonce_hash)=32),
 pkce_challenge varchar(43) NOT NULL,custody_key_id varchar(32) NOT NULL,custody_nonce bytea NOT NULL CHECK(octet_length(custody_nonce)=12),custody_ciphertext bytea NOT NULL CHECK(octet_length(custody_ciphertext) BETWEEN 59 AND 256),
 created_at timestamptz NOT NULL,attempt_deadline timestamptz NOT NULL,CHECK(created_at<attempt_deadline AND attempt_deadline<=created_at+interval '120 seconds'),UNIQUE(custody_key_id,custody_nonce),UNIQUE(retention_key_id,cookie_hash)
);
CREATE TABLE legacy_migration_account_claims (
 attempt_id uuid PRIMARY KEY REFERENCES legacy_migration_account_attempts(id),callback_hash bytea NOT NULL CHECK(octet_length(callback_hash)=32),started_at timestamptz NOT NULL,backend_pid integer NOT NULL,backend_start timestamptz NOT NULL,admission_txid bigint NOT NULL
);
CREATE TABLE legacy_migration_account_receipts (
 receipt_id uuid PRIMARY KEY,attempt_id uuid NOT NULL UNIQUE REFERENCES legacy_migration_account_attempts(id),owner_id uuid NOT NULL REFERENCES identities(id),
 proof_mode varchar(24) NOT NULL CHECK(proof_mode='LEGACY_OIDC_REAUTH'),issuer text NOT NULL,subject text NOT NULL,client_id text NOT NULL,user_session_id text NOT NULL,
 owner_generation bigint NOT NULL,association_generation bigint NOT NULL,credential_generation bigint NOT NULL,email_generation bigint NOT NULL,
 authenticated_at timestamptz NOT NULL,issued_at timestamptz NOT NULL,token_expires_at timestamptz NOT NULL,challenge_created_at timestamptz NOT NULL,callback_database_start timestamptz NOT NULL,account_deadline timestamptz NOT NULL,attempt_deadline timestamptz NOT NULL,
 retention_key_id varchar(32) NOT NULL,receipt_hash text NOT NULL CHECK(receipt_hash~'^[0-9a-f]{64}$'),CHECK(challenge_created_at<account_deadline AND account_deadline<=challenge_created_at+interval '120 seconds')
);
ALTER TABLE legacy_migration_intents ADD CONSTRAINT legacy_migration_account_receipt_fk FOREIGN KEY(account_receipt_id) REFERENCES legacy_migration_account_receipts(receipt_id);
CREATE TABLE legacy_migration_account_completions (
 attempt_id uuid PRIMARY KEY REFERENCES legacy_migration_account_attempts(id),migration_id uuid NOT NULL UNIQUE REFERENCES legacy_migration_intents(id),receipt_id uuid NOT NULL UNIQUE REFERENCES legacy_migration_account_receipts(receipt_id),completed_at timestamptz NOT NULL
);
CREATE INDEX legacy_migration_account_callback_lookup ON legacy_migration_account_attempts(retention_key_id,state_hash,cookie_hash);
CREATE TRIGGER legacy_migration_account_attempt_immutable BEFORE UPDATE OR DELETE ON legacy_migration_account_attempts FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_account_claim_immutable BEFORE UPDATE OR DELETE ON legacy_migration_account_claims FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_account_receipt_immutable BEFORE UPDATE OR DELETE ON legacy_migration_account_receipts FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_account_completion_immutable BEFORE UPDATE OR DELETE ON legacy_migration_account_completions FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();

-- Restricted browser binders, digest-only; no raw form/account/attempt cookie or CSRF retained.
CREATE TABLE legacy_migration_browser_forms (
 command_id uuid PRIMARY KEY,retention_key_id varchar(32) NOT NULL,cookie_hash bytea NOT NULL CHECK(octet_length(cookie_hash)=32),csrf_hash bytea NOT NULL CHECK(octet_length(csrf_hash)=32),
 created_at timestamptz NOT NULL,expires_at timestamptz NOT NULL,CHECK(created_at<expires_at AND expires_at<=created_at+interval '120 seconds'),UNIQUE(retention_key_id,cookie_hash)
);
CREATE TABLE legacy_migration_browser_accounts (
 command_id uuid PRIMARY KEY,migration_id uuid NOT NULL UNIQUE REFERENCES legacy_migration_intents(id),retention_key_id varchar(32) NOT NULL,cookie_hash bytea NOT NULL CHECK(octet_length(cookie_hash)=32),csrf_hash bytea NOT NULL CHECK(octet_length(csrf_hash)=32),
 created_at timestamptz NOT NULL,expires_at timestamptz NOT NULL,CHECK(created_at<expires_at AND expires_at<=created_at+interval '120 seconds'),UNIQUE(retention_key_id,cookie_hash)
);
CREATE TABLE legacy_migration_browser_consumptions (
 command_id uuid PRIMARY KEY,purpose varchar(20) NOT NULL CHECK(purpose IN ('LOCAL_PASSWORD','LEGACY_ACCOUNT','MANAGED_BINDING')),consumed_at timestamptz NOT NULL
);
CREATE TRIGGER legacy_migration_browser_form_immutable BEFORE UPDATE OR DELETE ON legacy_migration_browser_forms FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_browser_account_immutable BEFORE UPDATE OR DELETE ON legacy_migration_browser_accounts FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_browser_consumption_immutable BEFORE UPDATE OR DELETE ON legacy_migration_browser_consumptions FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
