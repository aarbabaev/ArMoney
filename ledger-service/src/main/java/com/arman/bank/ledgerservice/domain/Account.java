package com.arman.bank.ledgerservice.domain;
import java.util.UUID;
public record Account(UUID id, UUID walletId, UUID ownerId, String currency, long balanceMinor) {}
