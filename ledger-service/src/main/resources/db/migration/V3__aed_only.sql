-- Never reinterpret historical money. Operators must resolve legacy data explicitly.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM accounts WHERE currency <> 'AED')
        OR EXISTS (SELECT 1 FROM transfers WHERE currency <> 'AED')
        OR EXISTS (SELECT 1 FROM transfer_requests WHERE currency <> 'AED') THEN
        RAISE EXCEPTION 'AED-only migration refused: non-AED ledger data requires explicit operator resolution';
    END IF;
END;
$$;

ALTER TABLE accounts ADD CONSTRAINT accounts_aed_only CHECK (currency = 'AED');
ALTER TABLE transfers ADD CONSTRAINT transfers_aed_only CHECK (currency = 'AED');
ALTER TABLE transfer_requests ADD CONSTRAINT transfer_requests_aed_only CHECK (currency = 'AED');
