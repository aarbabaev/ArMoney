-- V1 scaffold rows have no trusted recipient/account mapping. Never invent one.
ALTER TABLE payments
    ADD COLUMN recipient_id UUID,
    ADD COLUMN recipient_phone VARCHAR(16),
    ADD COLUMN debit_account_id UUID,
    ADD COLUMN credit_account_id UUID,
    ADD COLUMN rejection_reason VARCHAR(32),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT resolved_mapping CHECK (
        (recipient_id IS NULL AND recipient_phone IS NULL AND debit_account_id IS NULL AND credit_account_id IS NULL)
        OR (recipient_id IS NOT NULL AND recipient_phone IS NOT NULL AND debit_account_id IS NOT NULL AND credit_account_id IS NOT NULL
            AND requester_id <> recipient_id AND debit_account_id <> credit_account_id
            AND recipient_phone ~ '^\+[1-9][0-9]{7,14}$' AND currency IN ('EUR','USD','GBP'))),
    ADD CONSTRAINT lease_pair CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    ADD CONSTRAINT rejection_matches_status CHECK (recipient_id IS NULL OR
        (status = 'REJECTED' AND rejection_reason IS NOT NULL AND rejection_reason IN ('INSUFFICIENT_FUNDS','INVALID_ACCOUNT','BALANCE_LIMIT'))
        OR (status <> 'REJECTED' AND rejection_reason IS NULL));

CREATE INDEX payment_recovery ON payments(next_attempt_at, created_at, id)
    WHERE status = 'PENDING' AND recipient_id IS NOT NULL;
CREATE INDEX payment_sender_history ON payments(requester_id, created_at DESC, id DESC);
CREATE INDEX payment_recipient_history ON payments(recipient_id, created_at DESC, id DESC) WHERE status = 'COMPLETED';

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    payment_id UUID NOT NULL REFERENCES payments(id),
    type VARCHAR(20) NOT NULL CHECK (type IN ('PAYMENT_COMPLETED','PAYMENT_REJECTED','PAYMENT_RECEIVED')),
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('EUR','USD','GBP')),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMPTZ,
    UNIQUE(owner_id, payment_id, type)
);
CREATE INDEX notification_owner_history ON notifications(owner_id, created_at DESC, id DESC);

-- Mapping and command identity are immutable once accepted. Lease metadata may change while pending.
CREATE FUNCTION preserve_payment_command() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.requester_id, NEW.idempotency_key, NEW.request_hash, NEW.source_wallet_id,
        NEW.destination_wallet_id, NEW.currency, NEW.amount_minor, NEW.recipient_id, NEW.recipient_phone,
        NEW.debit_account_id, NEW.credit_account_id, NEW.created_at)
        IS DISTINCT FROM
       (OLD.id, OLD.requester_id, OLD.idempotency_key, OLD.request_hash, OLD.source_wallet_id,
        OLD.destination_wallet_id, OLD.currency, OLD.amount_minor, OLD.recipient_id, OLD.recipient_phone,
        OLD.debit_account_id, OLD.credit_account_id, OLD.created_at) THEN
        RAISE EXCEPTION 'Payment command is immutable';
    END IF;
    IF OLD.status <> 'PENDING' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Terminal payment is immutable';
    END IF;
    IF OLD.recipient_id IS NULL AND NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION 'Legacy payment cannot be processed';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_payment BEFORE UPDATE ON payments FOR EACH ROW EXECUTE FUNCTION preserve_payment_command();
