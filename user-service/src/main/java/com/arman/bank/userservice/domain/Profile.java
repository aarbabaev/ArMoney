package com.arman.bank.userservice.domain;
import java.util.UUID;
import java.util.Objects;
public record Profile(UUID id, UUID identityId, String displayName) {
    public Profile {
        Objects.requireNonNull(id);
        Objects.requireNonNull(identityId);
        if (displayName == null) throw new IllegalArgumentException("Display name required");
        displayName = displayName.strip();
        if (displayName.isEmpty() || displayName.codePointCount(0, displayName.length()) > 100 ||
            displayName.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid display name");
    }
}
