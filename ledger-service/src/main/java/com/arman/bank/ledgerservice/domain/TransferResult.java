package com.arman.bank.ledgerservice.domain;
public record TransferResult(Transfer transfer, Outcome outcome) {
    public enum Outcome { POSTED, INSUFFICIENT_FUNDS, INVALID_ACCOUNT, BALANCE_LIMIT }
}
