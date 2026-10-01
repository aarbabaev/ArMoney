package com.arman.bank.walletservice.application;
import com.arman.bank.walletservice.domain.Wallet;
public interface LedgerBalances {
    long balance(Wallet wallet) throws Exception;
}
