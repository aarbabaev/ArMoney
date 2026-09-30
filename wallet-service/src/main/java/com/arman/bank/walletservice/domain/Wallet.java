package com.arman.bank.walletservice.domain;
import java.util.UUID;
import java.util.Set;
import java.util.Objects;
/** Metadata only: ACTIVE does not imply a funded ledger account. */
public record Wallet(UUID id, UUID ownerId, String currency, String status) {
    public Wallet {
        Objects.requireNonNull(id);
        Objects.requireNonNull(ownerId);
        if (!Set.of("EUR", "USD", "GBP").contains(currency == null ? "" : currency))
            throw new IllegalArgumentException("Unsupported currency");
        if (!Set.of("ACTIVE", "CLOSED").contains(status == null ? "" : status))
            throw new IllegalArgumentException("Invalid status");
    }
}
