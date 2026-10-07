-- Contact data is independent of login credentials and never links identities.
ALTER TABLE identities ADD COLUMN notification_email VARCHAR(254);
ALTER TABLE identities ADD COLUMN notification_email_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE identities ADD CONSTRAINT notification_email_verification
    CHECK (NOT notification_email_verified OR notification_email IS NOT NULL);
