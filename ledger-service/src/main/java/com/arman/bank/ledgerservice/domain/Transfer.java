package com.arman.bank.ledgerservice.domain;

import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/** One same-currency P2P transfer; amounts are positive integer minor units. */
public record Transfer(UUID paymentId, UUID debitAccountId, UUID creditAccountId,
                       Currency currency, long amountMinor) {
    public Transfer {
        Objects.requireNonNull(paymentId);
        Objects.requireNonNull(debitAccountId);
        Objects.requireNonNull(creditAccountId);
        Objects.requireNonNull(currency);
        if (debitAccountId.equals(creditAccountId)) throw new IllegalArgumentException("Accounts must differ");
        if (amountMinor <= 0) throw new IllegalArgumentException("Amount must be positive");
        if (currency.getDefaultFractionDigits() < 0) throw new IllegalArgumentException("Unsupported currency");
    }
}
