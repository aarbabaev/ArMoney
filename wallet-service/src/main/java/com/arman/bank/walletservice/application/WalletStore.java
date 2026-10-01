package com.arman.bank.walletservice.application;
import com.arman.bank.walletservice.domain.Wallet;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
public interface WalletStore {
    Wallet createOrGet(Wallet wallet);
    List<Wallet> list(UUID owner);
    Optional<Wallet> find(UUID id);
    Optional<Wallet> find(UUID owner, String currency);
}
