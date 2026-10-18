package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.MigrationUserCurrentPort;
import com.arman.bank.runtime.Database;
import java.util.Objects;
import java.util.Optional;

/** Explicit default-OFF composition; borrowed REG authority/database are never replaced or closed here. */
public final class MigrationUserRevisionResources implements AutoCloseable {
    private final LegacyMigrationManagedProofCrypto crypto;
    private final HttpMigrationUserCurrent current;
    private final MigrationUserMutationRoutes routes;
    private boolean closed;
    private MigrationUserRevisionResources(LegacyMigrationManagedProofCrypto crypto,HttpMigrationUserCurrent current,MigrationUserMutationRoutes routes){this.crypto=crypto;this.current=current;this.routes=routes;}
    public static Optional<MigrationUserRevisionResources> open(Database database,LegacyMigrationConfiguration migration,
            MigrationUserRevisionConfiguration revision,VerificationAuthorityConfiguration.Resources registration){
        Objects.requireNonNull(revision);if(!revision.enabled())return Optional.empty();
        Objects.requireNonNull(database);Objects.requireNonNull(migration);Objects.requireNonNull(registration);
        if(!migration.enabled)throw new IllegalArgumentException("Migration User observation dependencies unavailable");
        // Private-constructed REG resources retain the genuine completion authority adapter.
        // Never call VerificationProjectionConfiguration.store(database)'s unavailable default.
        var projections=registration.registrationProjections();String writer=registration.registrationProjectionWriter();
        var crypto=new LegacyMigrationManagedProofCrypto(migration.proofId,migration.proof());
        HttpMigrationUserCurrent current=HttpMigrationUserCurrent.open(revision).orElseThrow(()->new IllegalStateException("Migration User observation unavailable"));
        try{
            var mutation=new PostgresMigrationUserMutationStore(database,projections,writer,crypto);
            return Optional.of(new MigrationUserRevisionResources(crypto,current,new MigrationUserMutationRoutes(revision,mutation)));
        }catch(RuntimeException|Error failure){try{current.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    public synchronized LegacyMigrationManagedProofCrypto crypto(){requireOpen();return crypto;}
    public synchronized MigrationUserCurrentPort userCurrent(){requireOpen();return current;}
    public synchronized MigrationUserMutationRoutes mutationRoutes(){requireOpen();return routes;}
    private void requireOpen(){if(closed)throw new IllegalStateException("Migration User revision resources closed");}
    @Override public synchronized void close(){if(closed)return;closed=true;current.close();}
    @Override public String toString(){return "MigrationUserRevisionResources[redacted]";}
}
