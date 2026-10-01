package com.arman.bank.paymentservice.domain;

import java.time.Instant;
import java.util.UUID;

public record Notification(UUID id, UUID paymentId, String type, String currency, long amountMinor,
                           Instant createdAt, Instant readAt) {}
