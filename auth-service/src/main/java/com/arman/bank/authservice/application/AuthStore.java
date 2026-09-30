package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.Identity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthStore {
    Identity externalIdentity(String issuer, String subject);
    record Credentials(Identity identity, String passwordHash) {}
    void register(Identity identity, String passwordHash);
    Optional<Credentials> findByEmail(String email);
    boolean allowAttempt(String subjectHash, Instant now);
    void saveSession(String tokenHash, UUID identityId, Instant now, Instant expiresAt);
    Optional<Identity> findSession(String tokenHash, Instant now);
    void revoke(String tokenHash);
}
