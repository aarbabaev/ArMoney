-- No historical backfill.
CREATE TABLE email_outbox (
 id UUID PRIMARY KEY, owner_id UUID NOT NULL, payment_id UUID NOT NULL REFERENCES payments(id),
 type VARCHAR(20) NOT NULL CHECK (type IN ('PAYMENT_COMPLETED','PAYMENT_REJECTED','PAYMENT_RECEIVED')),
 amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
 status VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','SKIPPED','DEAD')),
 recipient_email VARCHAR(254), recipient_verified BOOLEAN,
 delivery_mode VARCHAR(8) CHECK (delivery_mode IN ('sandbox','sending')),
 attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 8),
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 lease_token UUID, lease_until TIMESTAMPTZ, error_code VARCHAR(40),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(owner_id,payment_id,type),
 FOREIGN KEY(owner_id,payment_id,type) REFERENCES notifications(owner_id,payment_id,type),
 CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
 CHECK ((recipient_email IS NULL) = (recipient_verified IS NULL))
);
CREATE INDEX email_outbox_ready ON email_outbox(next_attempt_at,created_at,id) WHERE status = 'PENDING';
CREATE FUNCTION preserve_email_recipient() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.recipient_email IS NOT NULL AND (NEW.recipient_email, NEW.recipient_verified) IS DISTINCT FROM (OLD.recipient_email, OLD.recipient_verified) THEN
  RAISE EXCEPTION 'Email recipient is immutable';
 END IF;
 IF OLD.delivery_mode IS NOT NULL AND NEW.delivery_mode IS DISTINCT FROM OLD.delivery_mode THEN
  RAISE EXCEPTION 'Email delivery mode is immutable';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_email_recipient BEFORE UPDATE ON email_outbox FOR EACH ROW EXECUTE FUNCTION preserve_email_recipient();
