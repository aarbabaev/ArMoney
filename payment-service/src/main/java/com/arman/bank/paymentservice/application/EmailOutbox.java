package com.arman.bank.paymentservice.application;
import java.util.*;
public interface EmailOutbox {
    record Claim(UUID id, UUID ownerId, UUID paymentId, String type, long amountMinor,
                 UUID token, int attempts, String recipientEmail, Boolean recipientVerified, String recipientName) {
        public Claim(UUID id, UUID ownerId, UUID paymentId, String type, long amountMinor,
                     UUID token, int attempts, String recipientEmail, Boolean recipientVerified) {
            this(id, ownerId, paymentId, type, amountMinor, token, attempts, recipientEmail, recipientVerified, null);
        }
        public Claim withRecipient(EmailDelivery.Recipient recipient) {
            return new Claim(id, ownerId, paymentId, type, amountMinor, token, attempts,
                recipient.email(), recipient.verified(), recipient.displayName());
        }
    }
    Optional<Claim> claim(String mode);
    boolean freezeRecipient(Claim claim, EmailDelivery.Recipient recipient);
    boolean finish(Claim claim, String status, String code);
    void retry(Claim claim, String code);
}
