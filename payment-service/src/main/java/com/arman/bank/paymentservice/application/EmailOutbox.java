package com.arman.bank.paymentservice.application;
import java.util.*;
public interface EmailOutbox {
    record Claim(UUID id, UUID ownerId, UUID paymentId, String type, long amountMinor,
                 UUID token, int attempts, String recipientEmail, Boolean recipientVerified) {}
    Optional<Claim> claim(String mode);
    boolean freezeRecipient(Claim claim, EmailDelivery.Recipient recipient);
    boolean finish(Claim claim, String status, String code);
    void retry(Claim claim, String code);
}
