-- Internal immutable evidence only. No ACTIVE/public/session authority and no legacy backfill.
CREATE TABLE banking_eligibility_grants (
    identity_id uuid NOT NULL,
    verification_epoch bigint NOT NULL CHECK(verification_epoch>0),
    enrollment_id uuid NOT NULL,
    association_digest bytea NOT NULL CHECK(octet_length(association_digest)=32),
    email_candidate_id uuid NOT NULL,
    phone_candidate_id uuid NOT NULL,
    email_receipt_id uuid NOT NULL,
    phone_receipt_id uuid NOT NULL,
    email_contact_version integer NOT NULL CHECK(email_contact_version>0),
    phone_contact_version integer NOT NULL CHECK(phone_contact_version>0),
    projection_operation_id uuid NOT NULL,
    projection_revision integer NOT NULL CHECK(projection_revision>0),
    projection_payload_hash varchar(64) NOT NULL CHECK(projection_payload_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    enrollment_expires_at timestamptz NOT NULL,
    authority_expires_at timestamptz NOT NULL,
    PRIMARY KEY(identity_id,verification_epoch),
    FOREIGN KEY(identity_id,enrollment_id) REFERENCES managed_registration_principals(identity_id,enrollment_id) ON DELETE RESTRICT,
    CHECK(expires_at>created_at AND expires_at<=enrollment_expires_at AND expires_at<=authority_expires_at)
);
-- IDs and hashes intentionally do not foreign-key contact/receipt/authority rows.
CREATE TRIGGER banking_eligibility_grant_immutable BEFORE UPDATE OR DELETE ON banking_eligibility_grants
FOR EACH ROW EXECUTE FUNCTION reject_managed_registration_mutation();
