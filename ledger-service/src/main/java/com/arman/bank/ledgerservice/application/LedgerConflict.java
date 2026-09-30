package com.arman.bank.ledgerservice.application;
public final class LedgerConflict extends RuntimeException {
    public LedgerConflict() { super("Request identity already used with different parameters"); }
}
