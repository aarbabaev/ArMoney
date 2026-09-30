package com.arman.bank.walletservice.domain;
import java.util.UUID;
import java.util.Set;
import java.util.Objects;
/** Wallet lifecycle and confirmed ledger mapping; balances belong to ledger. */
public record Wallet(UUID id, UUID ownerId, String currency, String status,
                     String provisioningStatus, UUID ledgerAccountId) {
    public Wallet(UUID id, UUID ownerId, String currency, String status) {
        this(id, ownerId, currency, status, "PENDING", null);
    }
    public Wallet {
        Objects.requireNonNull(id);
        Objects.requireNonNull(ownerId);
        if (!Set.of("EUR", "USD", "GBP").contains(currency == null ? "" : currency))
            throw new IllegalArgumentException("Unsupported currency");
        if (!Set.of("ACTIVE", "CLOSED").contains(status == null ? "" : status))
            throw new IllegalArgumentException("Invalid status");
        if (!("PENDING".equals(provisioningStatus) && ledgerAccountId == null
                || "READY".equals(provisioningStatus) && ledgerAccountId != null))
            throw new IllegalArgumentException("Invalid ledger mapping");
    }
}
