package com.arman.bank.walletservice.application;
import com.arman.bank.walletservice.domain.Wallet;
import java.util.Optional;
import java.util.UUID;
public interface ProvisioningStore {
    record Claim(Wallet wallet, UUID token, int attempt) {}
    Optional<Claim> claim();
    boolean complete(Claim claim, UUID ledgerAccountId);
    void retry(Claim claim, int delaySeconds);
}
