-- Optional profile display name, captured with the destination, never backfilled.
ALTER TABLE email_outbox ADD COLUMN recipient_name VARCHAR(100),
 ADD CONSTRAINT email_name_requires_recipient CHECK (recipient_name IS NULL OR recipient_email IS NOT NULL);

CREATE OR REPLACE FUNCTION preserve_email_recipient() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.recipient_email IS NOT NULL AND
    (NEW.recipient_email, NEW.recipient_verified, NEW.recipient_name) IS DISTINCT FROM
    (OLD.recipient_email, OLD.recipient_verified, OLD.recipient_name) THEN
  RAISE EXCEPTION 'Email recipient is immutable';
 END IF;
 IF OLD.delivery_mode IS NOT NULL AND NEW.delivery_mode IS DISTINCT FROM OLD.delivery_mode THEN
  RAISE EXCEPTION 'Email delivery mode is immutable';
 END IF;
 RETURN NEW;
END;
$$;
