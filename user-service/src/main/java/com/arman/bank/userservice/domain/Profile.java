package com.arman.bank.userservice.domain;
import java.util.UUID;
import java.util.Objects;
public record Profile(UUID id, UUID identityId, String displayName, String phoneNumber, boolean phoneVerified) {
    public Profile(UUID id, UUID identityId, String displayName) { this(id, identityId, displayName, null, false); }
    public Profile {
        Objects.requireNonNull(id);
        Objects.requireNonNull(identityId);
        if (phoneNumber != null && !phoneNumber.matches("\\+[1-9][0-9]{7,14}")) throw new IllegalArgumentException("Canonical E.164 phone required");
        if (phoneVerified && phoneNumber == null) throw new IllegalArgumentException("Verified phone required");
        if (displayName == null) throw new IllegalArgumentException("Display name required");
        displayName = displayName.strip();
        if (displayName.isEmpty() || displayName.codePointCount(0, displayName.length()) > 100 ||
            displayName.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid display name");
    }
    public static String validatePhone(String phone) {
        if (phone == null || !phone.matches("\\+9715[024568][0-9]{7}")) throw new IllegalArgumentException("Canonical UAE mobile required");
        return phone;
    }
}
