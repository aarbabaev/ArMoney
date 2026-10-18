package com.arman.bank.authservice.application;

import java.time.Instant;
import java.util.UUID;

/** Restricted managed binding only: no profile allocation, mapping, marker, cutover or banking token. */
public interface MigrationManagedProofStore {
    Redirect start(UUID migrationId,String ownerCapability,UUID commandId);
    Result complete(UUID attemptId,MigrationManagedProofPort.Callback callback);
    record Redirect(UUID attemptId,String attemptCookie,String state,String nonce,String codeChallenge,Instant expiresAt){
        @Override public String toString(){return "MigrationManagedRedirect[redacted]";}
    }
    record Result(String state,Instant expiresAt){}
    /** Correct original callback was durably claimed; never retry/resurrect its provider code. */
    final class Consumed extends RuntimeException {private final com.arman.bank.authservice.domain.LegacyMigrationOtp.Error error;public Consumed(com.arman.bank.authservice.domain.LegacyMigrationOtp.Error error){super("Migration proof unavailable",null,false,false);this.error=java.util.Objects.requireNonNull(error);}public com.arman.bank.authservice.domain.LegacyMigrationOtp.Error error(){return error;}}

}
