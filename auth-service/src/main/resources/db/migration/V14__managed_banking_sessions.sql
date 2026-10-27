-- Dedicated managed admission and sessions. No backfill or change to generic marker denial.
CREATE TABLE managed_banking_operations (
 id uuid PRIMARY KEY, purpose varchar(8) NOT NULL CHECK(purpose IN ('EXCHANGE','VALIDATE')),
 issuer varchar(512) NOT NULL, subject varchar(255) NOT NULL, client_id varchar(128) NOT NULL,
 user_session_id varchar(255) NOT NULL, identity_id uuid NOT NULL REFERENCES identities(id),
 event_id uuid NOT NULL, proof_id uuid NOT NULL, authority_stamp bytea NOT NULL CHECK(octet_length(authority_stamp)=32),
 started_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, retained_until timestamptz NOT NULL,
 CHECK(started_at<expires_at AND retained_until>=expires_at+interval '5 minutes')
);
CREATE TABLE managed_banking_event_consumptions (
 issuer varchar(512) NOT NULL,event_id uuid NOT NULL,identity_id uuid NOT NULL REFERENCES identities(id),
 operation_id uuid NOT NULL UNIQUE REFERENCES managed_banking_operations(id),
 key_id varchar(32) NOT NULL, fingerprint bytea NOT NULL CHECK(octet_length(fingerprint)=32),
 retained_until timestamptz NOT NULL,PRIMARY KEY(issuer,event_id),UNIQUE(key_id,fingerprint)
);
CREATE TABLE managed_banking_sessions (
 token_hash varchar(64) PRIMARY KEY CHECK(token_hash ~ '^[0-9a-f]{64}$'),
 identity_id uuid NOT NULL REFERENCES identities(id),issuer varchar(512) NOT NULL,event_id uuid NOT NULL,
 subject varchar(255) NOT NULL,client_id varchar(128) NOT NULL,user_session_id varchar(255) NOT NULL,
 verification_epoch bigint NOT NULL CHECK(verification_epoch>0),
 email_contact_version integer NOT NULL CHECK(email_contact_version>0),phone_contact_version integer NOT NULL CHECK(phone_contact_version>0),
 projection_revision integer NOT NULL CHECK(projection_revision>0),proof_id uuid NOT NULL,
 created_at timestamptz NOT NULL,expires_at timestamptz NOT NULL,CHECK(created_at<expires_at),
 FOREIGN KEY(issuer,event_id) REFERENCES managed_banking_event_consumptions(issuer,event_id),UNIQUE(issuer,event_id)
);
CREATE TABLE managed_banking_completions (
 operation_id uuid PRIMARY KEY REFERENCES managed_banking_operations(id),completed_at timestamptz NOT NULL,
 verdict varchar(8) NOT NULL CHECK(verdict IN ('ACCEPTED','DENIED'))
);
CREATE INDEX managed_banking_operation_retention ON managed_banking_operations(retained_until,id);
CREATE INDEX managed_banking_consumption_retention ON managed_banking_event_consumptions(retained_until,issuer,event_id);
CREATE INDEX managed_banking_session_expiry ON managed_banking_sessions(expires_at,token_hash);
CREATE FUNCTION managed_banking_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'immutable managed banking record' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='managed_banking_operations' AND clock_timestamp()<=OLD.retained_until THEN
  RAISE EXCEPTION 'retained managed banking operation' USING ERRCODE='23514';
 ELSIF TG_TABLE_NAME='managed_banking_event_consumptions' AND clock_timestamp()<=OLD.retained_until THEN
  RAISE EXCEPTION 'retained managed banking consumption' USING ERRCODE='23514';
 ELSIF TG_TABLE_NAME='managed_banking_completions' AND EXISTS(SELECT 1 FROM managed_banking_operations WHERE id=OLD.operation_id AND retained_until>=clock_timestamp()) THEN
  RAISE EXCEPTION 'retained managed banking completion' USING ERRCODE='23514';
 END IF;RETURN OLD;
END $$;
CREATE TRIGGER managed_banking_operation_immutable BEFORE UPDATE OR DELETE ON managed_banking_operations FOR EACH ROW EXECUTE FUNCTION managed_banking_immutable();
CREATE TRIGGER managed_banking_consumption_immutable BEFORE UPDATE OR DELETE ON managed_banking_event_consumptions FOR EACH ROW EXECUTE FUNCTION managed_banking_immutable();
CREATE TRIGGER managed_banking_completion_immutable BEFORE UPDATE OR DELETE ON managed_banking_completions FOR EACH ROW EXECUTE FUNCTION managed_banking_immutable();

CREATE TABLE managed_banking_cleanup (id integer PRIMARY KEY CHECK(id=1),swept_at timestamptz NOT NULL);

-- An unresolved/uncertain exchange cannot issue a second observation capability for this event.
CREATE UNIQUE INDEX managed_banking_one_exchange ON managed_banking_operations(issuer,event_id) WHERE purpose='EXCHANGE';

CREATE TRIGGER managed_banking_session_no_update BEFORE UPDATE ON managed_banking_sessions FOR EACH ROW EXECUTE FUNCTION managed_banking_immutable();
