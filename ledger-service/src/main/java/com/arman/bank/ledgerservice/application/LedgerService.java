package com.arman.bank.ledgerservice.application;
import com.arman.bank.ledgerservice.domain.*;
import java.util.*;
public final class LedgerService {
    private final LedgerStore store;
    public LedgerService(LedgerStore store) { this.store = store; }
    public Account open(UUID owner, UUID wallet, String currency) {
        Objects.requireNonNull(owner); Objects.requireNonNull(wallet);
        currency(currency);
        return store.open(owner, wallet, currency);
    }
    public Optional<Account> account(UUID owner, UUID id) { return store.account(owner, id); }
    public TransferResult post(UUID requester, Transfer transfer) {
        Objects.requireNonNull(requester); Objects.requireNonNull(transfer);
        currency(transfer.currency().getCurrencyCode());
        return store.post(requester, transfer);
    }
    public Optional<TransferResult> result(UUID requester, UUID payment) { return store.result(requester, payment); }
    private static void currency(String value) {
        if (!"AED".equals(value))
            throw new IllegalArgumentException("Unsupported currency");
    }
}
