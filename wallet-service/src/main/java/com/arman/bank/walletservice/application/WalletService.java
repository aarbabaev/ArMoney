package com.arman.bank.walletservice.application;
import com.arman.bank.walletservice.domain.Wallet;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
public final class WalletService {
    private final WalletStore store;
    public WalletService(WalletStore store) { this.store = store; }
    public Wallet open(UUID owner, String currency) { return store.createOrGet(new Wallet(UUID.randomUUID(), owner, currency, "ACTIVE")); }
    public List<Wallet> list(UUID owner) { return store.list(owner); }
    public Optional<Wallet> find(UUID id) { return store.find(id); }
    public Optional<Wallet> find(UUID owner, String currency) {
        if (!java.util.Set.of("EUR", "USD", "GBP").contains(currency)) throw new IllegalArgumentException("Unsupported currency");
        return store.find(owner, currency);
    }
}
