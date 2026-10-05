package com.arman.bank.paymentservice.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public record PaymentRequest(UUID sourceWalletId, UUID recipientId, String recipientPhone,
                             String currency, long amountMinor) {
    public PaymentRequest {
        Objects.requireNonNull(sourceWalletId);
        Objects.requireNonNull(recipientId);
        if (recipientPhone == null || !recipientPhone.matches("\\+[1-9][0-9]{7,14}"))
            throw new IllegalArgumentException("Invalid phone");
        if (!"AED".equals(currency) || amountMinor <= 0)
            throw new IllegalArgumentException("Invalid amount or currency");
    }

    public String hash() {
        // Every field has a validated alphabet without newlines, making this encoding unambiguous.
        String canonical = sourceWalletId + "\n" + recipientId + "\n" + recipientPhone + "\n" + currency + "\n" + amountMinor;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
