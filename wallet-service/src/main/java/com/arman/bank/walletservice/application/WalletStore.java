package com.arman.bank.walletservice.application;
import com.arman.bank.walletservice.domain.Wallet;
import java.util.List;
import java.util.UUID;
public interface WalletStore {
    Wallet createOrGet(Wallet wallet);
    List<Wallet> list(UUID owner);
}
