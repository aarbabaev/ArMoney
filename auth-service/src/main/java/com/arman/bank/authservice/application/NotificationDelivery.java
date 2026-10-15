package com.arman.bank.authservice.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface NotificationDelivery {
    enum Result { ACCEPTED, TRANSIENT_FAILURE, PERMANENT_FAILURE }
    record Claim(UUID id, UUID lease, String recipient, Instant occurredAt, int attempts) {
        @Override public String toString() { return "LoginNotificationClaim[REDACTED]"; }
    }
    interface Outbox {
        Optional<Claim> claim(Instant now);
        void finish(Claim claim, Result result, Instant now);
    }
    Result send(Claim claim) throws InterruptedException;
}
