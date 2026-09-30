package com.arman.bank.ledgerservice.application;
import com.arman.bank.ledgerservice.domain.*;
import java.util.Optional;
import java.util.UUID;
public interface LedgerStore {
    Account open(UUID owner, UUID wallet, String currency);
    Optional<Account> account(UUID owner, UUID id);
    TransferResult post(UUID requester, Transfer transfer);
    Optional<TransferResult> result(UUID requester, UUID payment);
}
