-- Existing V1 entries remain the source of truth; refuse invalid legacy balances rather than invent funding.
ALTER TABLE accounts ADD COLUMN owner_id UUID;
ALTER TABLE accounts ADD COLUMN account_kind VARCHAR(10) NOT NULL DEFAULT 'CUSTOMER'
    CHECK (account_kind IN ('CUSTOMER', 'CLEARING'));
ALTER TABLE accounts ADD COLUMN balance_minor BIGINT NOT NULL DEFAULT 0;
UPDATE accounts a SET balance_minor = COALESCE(
    (SELECT sum(p.amount_minor) FROM postings p WHERE p.account_id = a.id), 0);
ALTER TABLE accounts ADD CONSTRAINT customer_nonnegative
    CHECK (account_kind = 'CLEARING' OR balance_minor >= 0);
CREATE INDEX accounts_owner_idx ON accounts(owner_id);

CREATE FUNCTION preserve_account_identity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.wallet_id, NEW.currency, NEW.owner_id, NEW.account_kind)
        IS DISTINCT FROM (OLD.id, OLD.wallet_id, OLD.currency, OLD.owner_id, OLD.account_kind) THEN
        RAISE EXCEPTION 'Account identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER accounts_identity_immutable BEFORE UPDATE ON accounts
FOR EACH ROW EXECUTE FUNCTION preserve_account_identity();

-- Both projected balances and immutable paired postings are changed in the same transaction.
CREATE FUNCTION apply_transfer_balances() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM id FROM accounts WHERE id IN (NEW.debit_account_id, NEW.credit_account_id) ORDER BY id FOR UPDATE;
    UPDATE accounts SET balance_minor = balance_minor - NEW.amount_minor WHERE id = NEW.debit_account_id;
    UPDATE accounts SET balance_minor = balance_minor + NEW.amount_minor WHERE id = NEW.credit_account_id;
    RETURN NEW;
END;
$$;
CREATE TRIGGER transfers_apply_balances AFTER INSERT ON transfers
FOR EACH ROW EXECUTE FUNCTION apply_transfer_balances();
CREATE TRIGGER transfers_no_truncate BEFORE TRUNCATE ON transfers
FOR EACH STATEMENT EXECUTE FUNCTION reject_transfer_mutation();

CREATE TABLE transfer_requests (
    payment_id UUID PRIMARY KEY,
    requester_id UUID NOT NULL,
    debit_account_id UUID NOT NULL,
    credit_account_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK(amount_minor > 0),
    outcome VARCHAR(24) NOT NULL CHECK(outcome IN
        ('PENDING', 'POSTED', 'INSUFFICIENT_FUNDS', 'INVALID_ACCOUNT', 'BALANCE_LIMIT')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE FUNCTION protect_transfer_request() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Transfer results are immutable'; END IF;
    IF OLD.outcome <> 'PENDING' OR
       (NEW.payment_id, NEW.requester_id, NEW.debit_account_id, NEW.credit_account_id, NEW.currency, NEW.amount_minor, NEW.created_at)
       IS DISTINCT FROM
       (OLD.payment_id, OLD.requester_id, OLD.debit_account_id, OLD.credit_account_id, OLD.currency, OLD.amount_minor, OLD.created_at) THEN
        RAISE EXCEPTION 'Transfer request identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER requests_immutable BEFORE UPDATE OR DELETE ON transfer_requests
FOR EACH ROW EXECUTE FUNCTION protect_transfer_request();
CREATE TRIGGER requests_no_truncate BEFORE TRUNCATE ON transfer_requests
FOR EACH STATEMENT EXECUTE FUNCTION reject_transfer_mutation();

CREATE FUNCTION validate_transfer_result() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE r transfer_requests%ROWTYPE;
BEGIN
    SELECT * INTO r FROM transfer_requests WHERE payment_id = NEW.payment_id;
    IF r.outcome = 'PENDING' THEN RAISE EXCEPTION 'Cannot commit an unfinished ledger command'; END IF;
    IF r.outcome = 'POSTED' AND NOT EXISTS (
        SELECT 1 FROM transfers t WHERE t.payment_id = r.payment_id
        AND t.debit_account_id = r.debit_account_id AND t.credit_account_id = r.credit_account_id
        AND t.currency = r.currency AND t.amount_minor = r.amount_minor
    ) THEN RAISE EXCEPTION 'Posted result must match its journal entry'; END IF;
    IF r.outcome <> 'POSTED' AND EXISTS (SELECT 1 FROM transfers WHERE payment_id = r.payment_id)
        THEN RAISE EXCEPTION 'Rejected result cannot have a journal entry'; END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER requests_complete AFTER INSERT OR UPDATE ON transfer_requests
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_transfer_result();
