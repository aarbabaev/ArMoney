package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.Identity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthStore {
    default Identity externalIdentity(String issuer, String subject, String phone) {
        return externalIdentity(new SsoTokens.Principal(issuer, subject, phone));
    }
    Identity externalIdentity(SsoTokens.Principal principal);
    record EmailContact(String email, boolean verified) {
        @Override public String toString() { return "EmailContact[REDACTED]"; }
    }
    Optional<EmailContact> emailContact(UUID identityId);
    record Credentials(Identity identity, String passwordHash) {}
    Identity register(Identity identity, String passwordHash);
    Optional<Credentials> findByEmail(String email);
    boolean allowAttempt(String subjectHash, Instant now);
    void saveSession(String tokenHash, UUID identityId, Instant now, Instant expiresAt);
    Optional<Identity> findSession(String tokenHash, Instant now);
    void revoke(String tokenHash);
}
