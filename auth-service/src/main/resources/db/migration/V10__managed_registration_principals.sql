-- Permanent negative banking classification. No legacy backfill or positive grant.
CREATE TABLE managed_registration_principals (
    identity_id uuid PRIMARY KEY REFERENCES identities(id) ON DELETE RESTRICT,
    enrollment_id uuid NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(identity_id,enrollment_id)
);
-- Contact-free business replay protection outlives V8 full command cleanup.
CREATE TABLE registration_completion_intents (
    enrollment_id uuid NOT NULL REFERENCES managed_registration_principals(enrollment_id) ON DELETE RESTRICT,
    business_key bytea NOT NULL CHECK(octet_length(business_key)=32),
    operation_id uuid NOT NULL UNIQUE,
    identity_id uuid NOT NULL,
    verification_epoch bigint NOT NULL CHECK(verification_epoch>0),
    projection_revision integer NOT NULL CHECK(projection_revision>0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(enrollment_id,business_key),
    FOREIGN KEY(identity_id,enrollment_id) REFERENCES managed_registration_principals(identity_id,enrollment_id) ON DELETE RESTRICT
);
CREATE FUNCTION reject_managed_registration_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'managed registration records are immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER managed_registration_permanent BEFORE UPDATE OR DELETE ON managed_registration_principals
FOR EACH ROW EXECUTE FUNCTION reject_managed_registration_mutation();
CREATE TRIGGER registration_completion_permanent BEFORE UPDATE OR DELETE ON registration_completion_intents
FOR EACH ROW EXECUTE FUNCTION reject_managed_registration_mutation();
