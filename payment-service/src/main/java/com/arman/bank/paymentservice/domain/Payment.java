package com.arman.bank.paymentservice.domain;

import java.time.Instant;
import java.util.UUID;

public record Payment(UUID id, UUID requesterId, PaymentRequest request, UUID destinationWalletId,
                      UUID debitAccountId, UUID creditAccountId, String requestHash,
                      String status, String rejectionReason, Instant createdAt, Instant updatedAt) {}
