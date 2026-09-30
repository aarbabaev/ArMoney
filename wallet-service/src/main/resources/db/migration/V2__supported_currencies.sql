ALTER TABLE wallets ADD CONSTRAINT wallet_supported_currency CHECK (currency IN ('EUR', 'USD', 'GBP'));
