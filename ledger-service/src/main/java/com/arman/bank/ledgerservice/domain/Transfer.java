package com.arman.bank.ledgerservice.domain;

import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/** One AED P2P transfer; amounts are positive integer fils (100 fils = 1 AED). */
public record Transfer(UUID paymentId, UUID debitAccountId, UUID creditAccountId,
                       Currency currency, long amountMinor) {
    public Transfer {
        Objects.requireNonNull(paymentId);
        Objects.requireNonNull(debitAccountId);
        Objects.requireNonNull(creditAccountId);
        Objects.requireNonNull(currency);
        if (debitAccountId.equals(creditAccountId)) throw new IllegalArgumentException("Accounts must differ");
        if (amountMinor <= 0) throw new IllegalArgumentException("Amount must be positive");
        if (!"AED".equals(currency.getCurrencyCode())) throw new IllegalArgumentException("Unsupported currency");
    }
}
