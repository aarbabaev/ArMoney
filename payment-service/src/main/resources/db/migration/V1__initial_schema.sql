CREATE TABLE payments (
    id UUID PRIMARY KEY,
    requester_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    source_wallet_id UUID NOT NULL,
    destination_wallet_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (requester_id, idempotency_key),
    CHECK (source_wallet_id <> destination_wallet_id)
);
