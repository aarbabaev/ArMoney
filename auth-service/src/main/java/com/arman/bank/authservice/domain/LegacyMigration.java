package com.arman.bank.authservice.domain;

import java.time.Instant;
import java.util.UUID;

/** Restricted migration foundation; no publication, verified contact, bank token or completion authority. */
public final class LegacyMigration {
    private LegacyMigration() {}
    public enum ProofMode { LOCAL_PASSWORD, LEGACY_OIDC_REAUTH }
    public record Admission(UUID migrationId,UUID identityId,long ownerGeneration,long credentialGeneration,
                            Instant createdAt,Instant authorizationDeadline,String ownerCapability) {
        @Override public String toString(){return "LegacyMigrationAdmission[redacted]";}
    }
    public record Status(UUID migrationId,String state,Instant authorizationDeadline) {}
    public record ProofReceipt(UUID identityId,ProofMode mode,long ownerGeneration,long modeGeneration,
                               byte[] receiptHash,Instant expiresAt) {
        public ProofReceipt { receiptHash=receiptHash.clone(); }
        @Override public byte[] receiptHash(){return receiptHash.clone();}
        @Override public String toString(){return "LegacyAccountProof[redacted]";}
    }
}
