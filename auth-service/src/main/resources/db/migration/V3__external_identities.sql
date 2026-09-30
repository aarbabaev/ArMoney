-- Existing credentials and session foreign keys remain intact. SSO never links by email.
ALTER TABLE identities ALTER COLUMN email DROP NOT NULL;
ALTER TABLE identities ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE identities ADD CONSTRAINT credentials_pair CHECK ((email IS NULL) = (password_hash IS NULL));
CREATE TABLE external_identities (
    issuer VARCHAR(2048) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    identity_id UUID NOT NULL UNIQUE REFERENCES identities(id),
    PRIMARY KEY (issuer, subject),
    CHECK (length(issuer) > 0 AND length(subject) > 0)
);
