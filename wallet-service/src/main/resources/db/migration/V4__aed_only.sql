-- Refuse legacy currencies; changing a currency label would not convert money.
LOCK TABLE wallets IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM wallets WHERE currency <> 'AED') THEN
        RAISE EXCEPTION 'AED-only migration refused: non-AED wallets exist; explicit data remediation is required';
    END IF;
END;
$$;
ALTER TABLE wallets DROP CONSTRAINT wallet_supported_currency;
ALTER TABLE wallets ADD CONSTRAINT wallet_supported_currency CHECK (currency = 'AED');
