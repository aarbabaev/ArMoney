-- Internal staged recordings only. No proof, event, session or legacy backfill.
CREATE TABLE sso_code_intents (
 id uuid PRIMARY KEY,
 code_digest varchar(64) NOT NULL UNIQUE CHECK(code_digest ~ '^[0-9a-f]{64}$'),
 code_key_id varchar(32) NOT NULL,
 retention_key_id varchar(32) NOT NULL,
 actor_hash bytea NOT NULL CHECK(octet_length(actor_hash)=32),
 payload_hash bytea NOT NULL CHECK(octet_length(payload_hash)=32),
 binding_hash bytea NOT NULL CHECK(octet_length(binding_hash)=32),
 authority_hash bytea NOT NULL CHECK(octet_length(authority_hash)=32),
 issued_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL,
 admitted_at timestamptz NOT NULL,
 stage_cutoff timestamptz NOT NULL,
 retained_until timestamptz NOT NULL,
 CHECK(issued_at<expires_at AND expires_at<=issued_at+interval '120 seconds'),
 CHECK(stage_cutoff=issued_at+interval '30 seconds'),
 CHECK(retained_until=expires_at+interval '7 days')
);
CREATE INDEX sso_code_intent_retention ON sso_code_intents(retained_until);
CREATE TABLE sso_code_intent_encryption_keys (
 key_id varchar(32) PRIMARY KEY CHECK(key_id ~ '^[A-Za-z0-9_-]{1,32}$'),
 material_fingerprint bytea NOT NULL UNIQUE CHECK(octet_length(material_fingerprint)=32),
 state varchar(8) NOT NULL CHECK(state IN ('ACTIVE','RETIRED')),
 generation bigint NOT NULL CHECK(generation>0)
);
CREATE TABLE sso_code_intent_nonces (
 encryption_key_id varchar(32) NOT NULL REFERENCES sso_code_intent_encryption_keys(key_id),
 nonce bytea NOT NULL CHECK(octet_length(nonce)=12),
 PRIMARY KEY(encryption_key_id,nonce)
);
CREATE TABLE sso_code_intent_details (
 intent_id uuid PRIMARY KEY REFERENCES sso_code_intents(id),
 event_id uuid NOT NULL,
 password_authenticated_at timestamptz NOT NULL,
 erase_at timestamptz NOT NULL,
 encryption_key_id varchar(32) NOT NULL,
 encryption_generation bigint NOT NULL CHECK(encryption_generation>0),
 nonce bytea NOT NULL CHECK(octet_length(nonce)=12),
 ciphertext bytea NOT NULL CHECK(octet_length(ciphertext) BETWEEN 16 AND 1024),
 UNIQUE(encryption_key_id,nonce),
 FOREIGN KEY(encryption_key_id,nonce) REFERENCES sso_code_intent_nonces(encryption_key_id,nonce)
);
CREATE INDEX sso_code_intent_detail_retention ON sso_code_intent_details(erase_at);
CREATE TABLE sso_code_intent_commands (
 actor_hash bytea NOT NULL CHECK(octet_length(actor_hash)=32),
 command_id uuid NOT NULL,
 intent_id uuid NOT NULL REFERENCES sso_code_intents(id),
 alias_slot smallint NOT NULL CHECK(alias_slot BETWEEN 1 AND 4),
 UNIQUE(intent_id,alias_slot),
 PRIMARY KEY(actor_hash,command_id)
);
CREATE INDEX sso_code_intent_command_parent ON sso_code_intent_commands(intent_id);
CREATE TABLE sso_code_intent_admission (
 actor_hash bytea PRIMARY KEY CHECK(octet_length(actor_hash)=32),
 retention_key_id varchar(32) NOT NULL,
 credits numeric(20,6) NOT NULL CHECK(credits BETWEEN 0 AND 20),
 refilled_at timestamptz NOT NULL
);
CREATE TABLE sso_code_intent_cleanup (
 id integer PRIMARY KEY CHECK(id=1),
 swept_at timestamptz,
 healthy boolean NOT NULL DEFAULT false,
 capacity_alert boolean NOT NULL DEFAULT false
);
INSERT INTO sso_code_intent_cleanup(id) VALUES(1);
CREATE TABLE sso_login_creation_reservations (
 id uuid PRIMARY KEY,
 kind varchar(8) NOT NULL CHECK(kind IN ('INTENT','PROOF')),
 created_at timestamptz NOT NULL
);
CREATE INDEX sso_login_creation_hour ON sso_login_creation_reservations(created_at);
CREATE FUNCTION sso_code_intent_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE deadline timestamptz;
BEGIN
 IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'immutable code intent' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='sso_code_intents' THEN deadline:=OLD.retained_until;
 ELSIF TG_TABLE_NAME='sso_code_intent_details' THEN deadline:=OLD.erase_at;
 ELSE SELECT retained_until INTO deadline FROM sso_code_intents WHERE id=OLD.intent_id;
 END IF;
 IF deadline IS NULL OR clock_timestamp()<=deadline THEN
  RAISE EXCEPTION 'retained code intent' USING ERRCODE='23514';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER sso_code_intent_no_change BEFORE UPDATE OR DELETE ON sso_code_intents FOR EACH ROW EXECUTE FUNCTION sso_code_intent_immutable();
CREATE TRIGGER sso_code_intent_detail_no_change BEFORE UPDATE OR DELETE ON sso_code_intent_details FOR EACH ROW EXECUTE FUNCTION sso_code_intent_immutable();
CREATE TRIGGER sso_code_intent_command_no_change BEFORE UPDATE OR DELETE ON sso_code_intent_commands FOR EACH ROW EXECUTE FUNCTION sso_code_intent_immutable();

-- Permanent nonpersonal catalog prevents key-ID or material resurrection after nonce erasure.
CREATE FUNCTION sso_code_intent_key_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR NEW.key_id<>OLD.key_id OR NEW.material_fingerprint<>OLD.material_fingerprint
    OR OLD.state<>'ACTIVE' OR NEW.state<>'RETIRED' OR NEW.generation<>OLD.generation+1 THEN
   RAISE EXCEPTION 'immutable intent encryption catalog' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER sso_code_intent_key_no_reuse BEFORE UPDATE OR DELETE ON sso_code_intent_encryption_keys FOR EACH ROW EXECUTE FUNCTION sso_code_intent_key_guard();
CREATE FUNCTION sso_code_intent_nonce_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE key_state varchar(8);
BEGIN
 IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'immutable intent nonce' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
   SELECT state INTO key_state FROM sso_code_intent_encryption_keys WHERE key_id=NEW.encryption_key_id FOR UPDATE;
   IF key_state IS DISTINCT FROM 'ACTIVE' THEN RAISE EXCEPTION 'inactive intent nonce key' USING ERRCODE='23514'; END IF;
   RETURN NEW;
 END IF;
 SELECT state INTO key_state FROM sso_code_intent_encryption_keys WHERE key_id=OLD.encryption_key_id FOR UPDATE;
 IF key_state IS DISTINCT FROM 'RETIRED' OR EXISTS(SELECT 1 FROM sso_code_intent_details WHERE encryption_key_id=OLD.encryption_key_id) THEN
   RAISE EXCEPTION 'retained intent nonce' USING ERRCODE='23514';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER sso_code_intent_nonce_permanent BEFORE INSERT OR UPDATE OR DELETE ON sso_code_intent_nonces FOR EACH ROW EXECUTE FUNCTION sso_code_intent_nonce_guard();
