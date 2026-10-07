package com.arman.bank.authservice.domain;

import java.util.Objects;
import java.util.UUID;

public record Identity(UUID id, String email, String registrationPhone) {
    public Identity(UUID id, String email) { this(id, email, null); }
    public Identity {
        Objects.requireNonNull(id);
    }
}
