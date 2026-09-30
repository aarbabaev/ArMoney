ALTER TABLE wallets
    ADD COLUMN provisioning_status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN ledger_account_id UUID UNIQUE,
    ADD COLUMN provisioning_attempts INTEGER NOT NULL DEFAULT 0 CHECK (provisioning_attempts >= 0),
    ADD COLUMN next_provisioning_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN provisioning_token UUID,
    ADD CONSTRAINT wallet_mapping_state CHECK (
        (provisioning_status = 'PENDING' AND ledger_account_id IS NULL)
        OR (provisioning_status = 'READY' AND ledger_account_id IS NOT NULL)),
    ADD CONSTRAINT ready_wallet_has_no_lease CHECK (provisioning_status <> 'READY' OR provisioning_token IS NULL);
CREATE INDEX wallets_due_provisioning ON wallets(next_provisioning_at, id)
    WHERE status = 'ACTIVE' AND provisioning_status = 'PENDING';

CREATE FUNCTION protect_wallet_mapping() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.owner_id IS DISTINCT FROM OLD.owner_id
       OR NEW.currency IS DISTINCT FROM OLD.currency THEN
        RAISE EXCEPTION 'wallet identity is immutable';
    END IF;
    IF OLD.status = 'CLOSED' AND NEW.status <> 'CLOSED' THEN
        RAISE EXCEPTION 'closed wallets cannot reopen';
    END IF;
    IF OLD.provisioning_status = 'READY' AND
       (NEW.provisioning_status <> 'READY' OR NEW.ledger_account_id IS DISTINCT FROM OLD.ledger_account_id) THEN
        RAISE EXCEPTION 'confirmed ledger mapping is immutable';
    END IF;
    IF OLD.status = 'CLOSED' AND NEW.provisioning_status IS DISTINCT FROM OLD.provisioning_status THEN
        RAISE EXCEPTION 'closed wallets cannot be provisioned';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER wallets_mapping_immutable BEFORE UPDATE ON wallets
    FOR EACH ROW EXECUTE FUNCTION protect_wallet_mapping();
