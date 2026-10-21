package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LegacyMigration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;

/** Infrastructure-only caller-owned SQL contract. No service database borrow inside callbacks. */
interface MigrationBindingSqlPort {
    record BindingQuery(UUID migrationId,UUID ownerId,UUID receiptId,UUID reservationId,LegacyMigration.ProofMode accountProofMode,long ownerGeneration,long modeGeneration,long emailGeneration,Instant originalAccountDeadline){}
    <T>T withEvidence(PostgresLoginRequestScope.Request request,UUID migrationId,Instant originalStart,long startedNanos,Function<PostgresLegacyMigrationManagedProofStore.EvidenceUse,T> action);
    void lockTarget(DSLContext sql,UUID migrationId,PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence);
    String requireCurrent(DSLContext sql,BindingQuery query,PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence);
    void emailConsumed(DSLContext sql,BindingQuery query,PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence,UUID receipt,UUID challenge,UUID command);
    static MigrationBindingSqlPort unavailable(){return new MigrationBindingSqlPort(){
        public <T>T withEvidence(PostgresLoginRequestScope.Request request,UUID migrationId,Instant originalStart,long startedNanos,Function<PostgresLegacyMigrationManagedProofStore.EvidenceUse,T> action){throw LegacyMigrationManagedProofCrypto.unavailable();}
        public void lockTarget(DSLContext sql,UUID migrationId,PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence){throw LegacyMigrationManagedProofCrypto.unavailable();}
        public void emailConsumed(DSLContext sql,BindingQuery query,PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence,UUID receipt,UUID challenge,UUID command){throw LegacyMigrationManagedProofCrypto.unavailable();}
        public String requireCurrent(DSLContext sql,BindingQuery query,PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence){throw LegacyMigrationManagedProofCrypto.unavailable();}
    };}
}
