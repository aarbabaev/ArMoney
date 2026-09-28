CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    wallet_id UUID NOT NULL UNIQUE,
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    UNIQUE (id, currency)
);
-- One immutable transfer row represents two equal and opposite postings.
-- Balance/locking enforcement is required before exposing any write API.
CREATE TABLE transfers (
    payment_id UUID PRIMARY KEY,
    debit_account_id UUID NOT NULL,
    credit_account_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (debit_account_id, currency) REFERENCES accounts (id, currency),
    FOREIGN KEY (credit_account_id, currency) REFERENCES accounts (id, currency),
    CHECK (debit_account_id <> credit_account_id)
);
CREATE INDEX transfers_debit_idx ON transfers (debit_account_id);
CREATE INDEX transfers_credit_idx ON transfers (credit_account_id);
CREATE FUNCTION reject_transfer_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Ledger transfers are immutable; post a compensating transfer';
END;
$$;
CREATE TRIGGER transfers_immutable BEFORE UPDATE OR DELETE ON transfers
FOR EACH ROW EXECUTE FUNCTION reject_transfer_mutation();
CREATE VIEW postings AS
SELECT payment_id, debit_account_id AS account_id, currency, -amount_minor AS amount_minor, created_at FROM transfers
UNION ALL
SELECT payment_id, credit_account_id AS account_id, currency, amount_minor, created_at FROM transfers;
