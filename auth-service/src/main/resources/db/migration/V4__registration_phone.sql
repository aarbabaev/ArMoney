-- Historical identities remain unenrolled until a separate explicit enrollment flow.
ALTER TABLE identities ADD COLUMN registration_phone TEXT;
ALTER TABLE identities ADD CONSTRAINT canonical_registration_phone
    CHECK (registration_phone IS NULL OR registration_phone ~ '^\+9715[024568][0-9]{7}$');
ALTER TABLE identities ADD CONSTRAINT unique_registration_phone UNIQUE (registration_phone);
