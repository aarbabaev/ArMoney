-- Currency labels are part of immutable commands: never convert or relabel stored money.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM payments WHERE currency <> 'AED')
        OR EXISTS (SELECT 1 FROM notifications WHERE currency <> 'AED') THEN
        RAISE EXCEPTION 'AED-only migration requires no non-AED payments or notifications; existing money must not be relabeled';
    END IF;
END;
$$;

ALTER TABLE payments
    DROP CONSTRAINT payments_currency_check,
    ADD CONSTRAINT payments_currency_check CHECK (currency = 'AED'),
    DROP CONSTRAINT resolved_mapping,
    ADD CONSTRAINT resolved_mapping CHECK (
        (recipient_id IS NULL AND recipient_phone IS NULL AND debit_account_id IS NULL AND credit_account_id IS NULL)
        OR (recipient_id IS NOT NULL AND recipient_phone IS NOT NULL AND debit_account_id IS NOT NULL AND credit_account_id IS NOT NULL
            AND requester_id <> recipient_id AND debit_account_id <> credit_account_id
            AND recipient_phone ~ '^\+[1-9][0-9]{7,14}$' AND currency = 'AED'));

ALTER TABLE notifications
    DROP CONSTRAINT notifications_currency_check,
    ADD CONSTRAINT notifications_currency_check CHECK (currency = 'AED');
