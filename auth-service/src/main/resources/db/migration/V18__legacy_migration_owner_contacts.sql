-- Auth-owned verified contact is separate from published identities credential pair.
CREATE TABLE legacy_migration_contact_baselines (
 migration_id uuid PRIMARY KEY REFERENCES legacy_migration_intents(id),owner_id uuid NOT NULL REFERENCES identities(id),
 source text NOT NULL CHECK(source IN ('MIGRATION_OWNER_CONTACT','ABSENT_OWNER_CONTACT')),
 baseline_generation bigint NOT NULL,baseline_digest text,
 CHECK((source='ABSENT_OWNER_CONTACT' AND baseline_generation=0 AND baseline_digest IS NULL) OR(source='MIGRATION_OWNER_CONTACT' AND baseline_generation>0 AND baseline_digest ~ '^[0-9a-f]{64}$'))
);
CREATE TABLE legacy_migration_email_candidates (
 migration_id uuid PRIMARY KEY REFERENCES legacy_migration_intents(id),candidate_id uuid NOT NULL UNIQUE,owner_id uuid NOT NULL REFERENCES identities(id),
 account_receipt_id uuid NOT NULL REFERENCES legacy_migration_account_receipts(receipt_id),command_id uuid NOT NULL UNIQUE,
 canonical_email text NOT NULL CHECK(length(canonical_email)<=254),candidate_version bigint NOT NULL CHECK(candidate_version=1),
 retention_key_id varchar(32) NOT NULL,candidate_digest text NOT NULL CHECK(candidate_digest ~ '^[0-9a-f]{64}$'),
 account_deadline timestamptz NOT NULL,intent_deadline timestamptz NOT NULL,created_at timestamptz NOT NULL,CHECK(created_at<account_deadline AND account_deadline<=intent_deadline),
 next_command_id uuid NOT NULL UNIQUE,next_csrf_hash bytea NOT NULL CHECK(octet_length(next_csrf_hash)=32)
);
CREATE TABLE legacy_migration_owner_contacts (
 identity_id uuid PRIMARY KEY REFERENCES identities(id),canonical_email text NOT NULL CHECK(length(canonical_email)<=254),
 email_digest text NOT NULL CHECK(email_digest ~ '^[0-9a-f]{64}$'),generation bigint NOT NULL CHECK(generation>0),
 source_migration_id uuid NOT NULL UNIQUE REFERENCES legacy_migration_email_candidates(migration_id),
 source_receipt_id uuid NOT NULL UNIQUE REFERENCES legacy_migration_otp_receipts(id),verified_at timestamptz NOT NULL
);
CREATE TABLE legacy_migration_contact_transitions (
 migration_id uuid PRIMARY KEY REFERENCES legacy_migration_email_candidates(migration_id),owner_id uuid NOT NULL REFERENCES identities(id),
 receipt_id uuid NOT NULL UNIQUE REFERENCES legacy_migration_otp_receipts(id),consume_command_id uuid NOT NULL,
 resulting_generation bigint NOT NULL CHECK(resulting_generation>0),retention_key_id varchar(32) NOT NULL,
 transition_hash text NOT NULL CHECK(transition_hash ~ '^[0-9a-f]{64}$'),created_at timestamptz NOT NULL
);
ALTER TABLE legacy_migration_browser_accounts ADD COLUMN csrf_purpose text NOT NULL DEFAULT 'MANAGED_START' CHECK(csrf_purpose IN ('MANAGED_START','EMAIL_CANDIDATE'));
CREATE TRIGGER legacy_migration_contact_baseline_immutable BEFORE UPDATE OR DELETE ON legacy_migration_contact_baselines FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_email_candidate_immutable BEFORE UPDATE OR DELETE ON legacy_migration_email_candidates FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_owner_contact_immutable BEFORE UPDATE OR DELETE ON legacy_migration_owner_contacts FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_contact_transition_immutable BEFORE UPDATE OR DELETE ON legacy_migration_contact_transitions FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();

-- Retain both original immutable purpose commands, never overwrite the email form row.
ALTER TABLE legacy_migration_browser_accounts DROP CONSTRAINT legacy_migration_browser_accounts_migration_id_key;
-- PostgreSQL truncates generated long constraint names. Resolve only this exact fixed column tuple.
DO $migration$
DECLARE original_name text;
BEGIN
 SELECT conname INTO STRICT original_name FROM pg_constraint
 WHERE conrelid='legacy_migration_browser_accounts'::regclass AND contype='u'
 AND conkey=ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid='legacy_migration_browser_accounts'::regclass AND attname='retention_key_id'),(SELECT attnum FROM pg_attribute WHERE attrelid='legacy_migration_browser_accounts'::regclass AND attname='cookie_hash')]::smallint[];
 EXECUTE format('ALTER TABLE legacy_migration_browser_accounts DROP CONSTRAINT %I',original_name);
END $migration$;
ALTER TABLE legacy_migration_browser_accounts ADD CONSTRAINT legacy_migration_browser_account_purpose UNIQUE(migration_id,csrf_purpose);
ALTER TABLE legacy_migration_browser_accounts ADD CONSTRAINT legacy_migration_browser_cookie_purpose UNIQUE(retention_key_id,cookie_hash,csrf_purpose);
ALTER TABLE legacy_migration_browser_consumptions DROP CONSTRAINT legacy_migration_browser_consumptions_purpose_check;
ALTER TABLE legacy_migration_browser_consumptions ADD CONSTRAINT legacy_migration_browser_consumption_purpose CHECK(purpose IN ('LOCAL_PASSWORD','LEGACY_ACCOUNT','MANAGED_BINDING','EMAIL_CANDIDATE'));
ALTER TABLE legacy_migration_otp_stage_acks ADD COLUMN binding_version smallint NOT NULL DEFAULT 1 CHECK(binding_version IN (1,2));
