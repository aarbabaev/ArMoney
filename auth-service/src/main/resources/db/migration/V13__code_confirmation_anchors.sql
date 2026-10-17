-- Internal durable admission/results only. No provider wire, ACTIVE, token/session or legacy backfill.
CREATE TABLE sso_code_confirmation_anchors (
 id uuid PRIMARY KEY,
 intent_id uuid NOT NULL REFERENCES sso_code_intents(id),
 retention_key_id varchar(32) NOT NULL,
 actor_hash bytea NOT NULL CHECK(octet_length(actor_hash)=32),
 binding_hash bytea NOT NULL CHECK(octet_length(binding_hash)=32),
 confirmation_hash bytea NOT NULL CHECK(octet_length(confirmation_hash)=32),
 purpose varchar(6) NOT NULL CHECK(purpose IN ('FIRST','REPLAY')),
 generation smallint NOT NULL CHECK(generation BETWEEN 0 AND 31),
 started_at timestamptz NOT NULL,
 authority_hash bytea NOT NULL CHECK(octet_length(authority_hash)=32),
 verification_epoch bigint NOT NULL CHECK(verification_epoch>0),
 authority_revision bigint NOT NULL CHECK(authority_revision>=0),
 expires_at timestamptz NOT NULL,
 erase_at timestamptz NOT NULL,
 CHECK((purpose='FIRST' AND generation=0) OR (purpose='REPLAY' AND generation>0)),
 CHECK(started_at<expires_at AND erase_at=expires_at+interval '5 minutes'),
 UNIQUE(intent_id,purpose,generation)
);
CREATE UNIQUE INDEX sso_code_confirmation_one_first ON sso_code_confirmation_anchors(intent_id) WHERE purpose='FIRST';
CREATE INDEX sso_code_confirmation_anchor_retention ON sso_code_confirmation_anchors(erase_at,intent_id,id);
CREATE TABLE sso_code_confirmation_results (
 intent_id uuid PRIMARY KEY REFERENCES sso_code_intents(id),
 proof_id uuid NOT NULL UNIQUE,
 budget_origin_id uuid NOT NULL CHECK(budget_origin_id=intent_id),
 retention_key_id varchar(32) NOT NULL,
 actor_hash bytea NOT NULL CHECK(octet_length(actor_hash)=32),
 binding_hash bytea NOT NULL CHECK(octet_length(binding_hash)=32),
 payload_hash bytea NOT NULL CHECK(octet_length(payload_hash)=32),
 confirmation_hash bytea NOT NULL UNIQUE CHECK(octet_length(confirmation_hash)=32),
 expires_at timestamptz NOT NULL,
 retained_until timestamptz NOT NULL CHECK(retained_until=expires_at+interval '7 days')
);
CREATE INDEX sso_code_confirmation_result_retention ON sso_code_confirmation_results(retained_until,intent_id);
CREATE TABLE sso_code_confirmation_anchor_completions (
 anchor_id uuid PRIMARY KEY REFERENCES sso_code_confirmation_anchors(id),
 proof_id uuid NOT NULL,
 completed_at timestamptz NOT NULL
);
CREATE FUNCTION sso_code_confirmation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE deadline timestamptz;
BEGIN
 IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'immutable code confirmation' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='sso_code_confirmation_anchors' THEN deadline:=OLD.erase_at;
 ELSIF TG_TABLE_NAME='sso_code_confirmation_results' THEN deadline:=OLD.retained_until;
 ELSE SELECT erase_at INTO deadline FROM sso_code_confirmation_anchors WHERE id=OLD.anchor_id;
 END IF;
 IF deadline IS NULL OR clock_timestamp()<=deadline THEN
  RAISE EXCEPTION 'retained code confirmation' USING ERRCODE='23514';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER sso_code_confirmation_anchor_no_change BEFORE UPDATE OR DELETE ON sso_code_confirmation_anchors FOR EACH ROW EXECUTE FUNCTION sso_code_confirmation_immutable();
CREATE TRIGGER sso_code_confirmation_result_no_change BEFORE UPDATE OR DELETE ON sso_code_confirmation_results FOR EACH ROW EXECUTE FUNCTION sso_code_confirmation_immutable();
CREATE TRIGGER sso_code_confirmation_completion_no_change BEFORE UPDATE OR DELETE ON sso_code_confirmation_anchor_completions FOR EACH ROW EXECUTE FUNCTION sso_code_confirmation_immutable();
