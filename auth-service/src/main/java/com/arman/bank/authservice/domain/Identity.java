package com.arman.bank.authservice.domain;

import java.util.Objects;
import java.util.UUID;

public record Identity(UUID id, String email) {
    public Identity {
        Objects.requireNonNull(id);
        Objects.requireNonNull(email);
    }
}
