-- Foundation only. Counters start at a NEW post-schema baseline; no historical verification/backfill.
CREATE TABLE legacy_auth_generations (
 identity_id uuid PRIMARY KEY REFERENCES identities(id) ON DELETE CASCADE,owner_generation bigint NOT NULL DEFAULT 0 CHECK(owner_generation>=0),
 credential_generation bigint NOT NULL DEFAULT 0 CHECK(credential_generation>=0),email_generation bigint NOT NULL DEFAULT 0 CHECK(email_generation>=0)
);
INSERT INTO legacy_auth_generations(identity_id) SELECT id FROM identities;
CREATE TABLE legacy_auth_association_generations (
 issuer text NOT NULL,subject text NOT NULL,identity_id uuid NOT NULL REFERENCES identities(id),
 generation bigint NOT NULL DEFAULT 0 CHECK(generation>=0),active boolean NOT NULL DEFAULT true,PRIMARY KEY(issuer,subject)
);
INSERT INTO legacy_auth_association_generations(issuer,subject,identity_id) SELECT issuer,subject,identity_id FROM external_identities;
-- Stable row identity survives normal reads but never deletion/recreation of an old digest.
CREATE TABLE legacy_auth_session_rows (
 token_hash text PRIMARY KEY REFERENCES sessions(token_hash) ON DELETE CASCADE,row_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid()
);
INSERT INTO legacy_auth_session_rows(token_hash) SELECT token_hash FROM sessions;
CREATE FUNCTION legacy_auth_session_row() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN INSERT INTO legacy_auth_session_rows(token_hash) VALUES(NEW.token_hash);RETURN NEW;END $$;
CREATE TRIGGER legacy_auth_session_row AFTER INSERT ON sessions FOR EACH ROW EXECUTE FUNCTION legacy_auth_session_row();
CREATE TABLE legacy_migration_intents (
 id uuid PRIMARY KEY,identity_id uuid NOT NULL REFERENCES identities(id),command_id uuid NOT NULL,
 retention_key_id varchar(32) NOT NULL,command_hash bytea NOT NULL CHECK(octet_length(command_hash)=32),capability_hash bytea NOT NULL CHECK(octet_length(capability_hash)=32),
 owner_generation bigint NOT NULL CHECK(owner_generation>=0),credential_generation bigint NOT NULL CHECK(credential_generation>=0),
 email_generation bigint NOT NULL CHECK(email_generation>=0),proof_mode varchar(24) NOT NULL CHECK(proof_mode='LOCAL_PASSWORD'),
 state varchar(16) NOT NULL CHECK(state='OWNER_BOUND'),created_at timestamptz NOT NULL,authorization_deadline timestamptz NOT NULL,
 CHECK(authorization_deadline>created_at AND authorization_deadline<=created_at+interval '120 seconds'),UNIQUE(identity_id),UNIQUE(identity_id,command_id)
);
CREATE TABLE legacy_migration_continuity (
 migration_id uuid NOT NULL REFERENCES legacy_migration_intents(id),token_hash text NOT NULL,session_row_id uuid NOT NULL,identity_id uuid NOT NULL REFERENCES identities(id),
 owner_generation bigint NOT NULL CHECK(owner_generation>=0),original_created_at timestamptz NOT NULL,original_expires_at timestamptz NOT NULL,
 PRIMARY KEY(migration_id,token_hash),CHECK(original_created_at<original_expires_at)
);
-- No authority association may be inferred from an arbitrary pending enrollment. Future adapter must bind explicitly.
CREATE TABLE legacy_migration_associations (
 migration_id uuid PRIMARY KEY REFERENCES legacy_migration_intents(id),enrollment_id uuid NOT NULL UNIQUE REFERENCES verification_authorities(enrollment_id),
 owner_generation bigint NOT NULL CHECK(owner_generation>=0)
);
CREATE TABLE legacy_migration_pkce_custody (
 attempt_id uuid PRIMARY KEY,purpose varchar(16) NOT NULL CHECK(purpose IN ('LEGACY_ACCOUNT','MANAGED_BINDING')),
 key_id varchar(32) NOT NULL,nonce bytea NOT NULL CHECK(octet_length(nonce)=12),ciphertext bytea NOT NULL CHECK(octet_length(ciphertext) BETWEEN 59 AND 256),
 aad_hash bytea NOT NULL CHECK(octet_length(aad_hash)=32),created_at timestamptz NOT NULL,authorization_deadline timestamptz NOT NULL,
 CHECK(created_at<authorization_deadline),UNIQUE(key_id,nonce)
);
CREATE FUNCTION legacy_auth_identity_generation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN INSERT INTO legacy_auth_generations(identity_id) VALUES(NEW.id);RETURN NEW;END IF;
 IF NEW.password_hash IS DISTINCT FROM OLD.password_hash OR NEW.email IS DISTINCT FROM OLD.email THEN
  UPDATE legacy_auth_generations SET credential_generation=credential_generation+CASE WHEN NEW.password_hash IS DISTINCT FROM OLD.password_hash THEN 1 ELSE 0 END,
   email_generation=email_generation+CASE WHEN NEW.email IS DISTINCT FROM OLD.email THEN 1 ELSE 0 END WHERE identity_id=NEW.id;
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER legacy_auth_identity_generation AFTER INSERT OR UPDATE ON identities FOR EACH ROW EXECUTE FUNCTION legacy_auth_identity_generation();
CREATE FUNCTION legacy_auth_association_generation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN UPDATE legacy_auth_association_generations SET generation=generation+1,active=false WHERE issuer=OLD.issuer AND subject=OLD.subject;RETURN OLD;END IF;
 IF TG_OP='UPDATE' AND (NEW.issuer<>OLD.issuer OR NEW.subject<>OLD.subject) THEN
  UPDATE legacy_auth_association_generations SET generation=generation+1,active=false WHERE issuer=OLD.issuer AND subject=OLD.subject;
 END IF;
 INSERT INTO legacy_auth_association_generations(issuer,subject,identity_id) VALUES(NEW.issuer,NEW.subject,NEW.identity_id)
 ON CONFLICT(issuer,subject) DO UPDATE SET identity_id=EXCLUDED.identity_id,generation=legacy_auth_association_generations.generation+1,active=true;
 RETURN NEW;
END $$;
CREATE TRIGGER legacy_auth_association_generation AFTER INSERT OR UPDATE OR DELETE ON external_identities FOR EACH ROW EXECUTE FUNCTION legacy_auth_association_generation();
CREATE FUNCTION legacy_migration_foundation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'unresolved legacy migration retained' USING ERRCODE='23514';END $$;
CREATE TRIGGER legacy_migration_intent_immutable BEFORE UPDATE OR DELETE ON legacy_migration_intents FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_continuity_immutable BEFORE UPDATE OR DELETE ON legacy_migration_continuity FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
CREATE TRIGGER legacy_migration_association_immutable BEFORE UPDATE OR DELETE ON legacy_migration_associations FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();

CREATE TRIGGER legacy_migration_custody_immutable BEFORE UPDATE OR DELETE ON legacy_migration_pkce_custody FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();

CREATE FUNCTION legacy_auth_monotonic_generations() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.identity_id<>OLD.identity_id OR NEW.owner_generation<OLD.owner_generation OR NEW.credential_generation<OLD.credential_generation OR NEW.email_generation<OLD.email_generation THEN
  RAISE EXCEPTION 'nonmonotonic legacy generation' USING ERRCODE='23514';
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER legacy_auth_monotonic_generations BEFORE UPDATE ON legacy_auth_generations FOR EACH ROW EXECUTE FUNCTION legacy_auth_monotonic_generations();
CREATE TRIGGER legacy_auth_session_row_immutable BEFORE UPDATE ON legacy_auth_session_rows FOR EACH ROW EXECUTE FUNCTION legacy_migration_foundation_immutable();
