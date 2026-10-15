package com.arman.bank.paymentservice.application;
import java.util.UUID;
public interface EmailDelivery {
    record Recipient(String email, boolean verified, String displayName) {
        public Recipient(String email, boolean verified) { this(email, verified, null); }
    }
    enum Result { ACCEPTED, TRANSIENT_FAILURE, RATE_LIMITED, PERMANENT_FAILURE }
    Recipient recipient(UUID owner) throws Exception;
    Result send(EmailOutbox.Claim claim, String recipient) throws Exception;
}
