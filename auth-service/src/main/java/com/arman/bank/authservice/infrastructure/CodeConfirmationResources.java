package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.CodeConfirmationService;
import com.arman.bank.authservice.application.MigrationUserCurrentPort;
import com.arman.bank.authservice.domain.LoginProof;
import com.arman.bank.runtime.Database;
import io.javalin.config.JavalinConfig;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit default-off C2 owner. Optional Main ownership; no provider activation or banking/session grant.
 * The supplied Database and HTTP server remain caller-owned. Start maintenance only after HTTP startup. */
public final class CodeConfirmationResources implements AutoCloseable {
    private final CodeIntentCleanupWorker cleanup;
    private final HttpCodeSessionObservations observer;
    private final CodeIntentRoutes stageRoutes;
    private final CodeIntentKeyLookupRoutes lookupRoutes;
    private final CodeConfirmationRoutes confirmationRoutes;
    private final AtomicBoolean closed=new AtomicBoolean();
    private CodeConfirmationResources(CodeIntentCleanupWorker cleanup,HttpCodeSessionObservations observer,CodeIntentRoutes stageRoutes,
            CodeIntentKeyLookupRoutes lookupRoutes,CodeConfirmationRoutes confirmationRoutes){
        this.cleanup=cleanup;this.observer=observer;this.stageRoutes=stageRoutes;this.lookupRoutes=lookupRoutes;this.confirmationRoutes=confirmationRoutes;
    }
    public static Optional<CodeConfirmationResources> open(Database database,CodeConfirmationConfiguration configuration){
        return open(database,configuration,null,null);
    }
    /** Migration crypto and User-current observation are borrowed; the caller closes their owner after C2. */
    public static Optional<CodeConfirmationResources> open(Database database,CodeConfirmationConfiguration configuration,
            LegacyMigrationManagedProofCrypto migrationCrypto,MigrationUserCurrentPort userCurrent){
        Objects.requireNonNull(configuration);
        if(!configuration.enabled)return Optional.empty();
        if((migrationCrypto==null)!=(userCurrent==null))throw new IllegalArgumentException("Incomplete migration observation dependencies");
        Objects.requireNonNull(database);
        CodeIntentCleanupWorker cleanup=null;HttpCodeSessionObservations observer=null;
        try{
            var stage=configuration.stageConfiguration();var policy=stage.policy();
            // One borrowed Database; one set of original retention/AEAD objects and one maintenance owner.
            var intents=new PostgresCodeIntentStore(database,policy,stage.retentionKeys(),stage.detailCrypto(),migrationCrypto,userCurrent);
            cleanup=new CodeIntentCleanupWorker(intents);
            PostgresLoginProofStore proofs;
            byte[] proofKey=configuration.proofRetentionKey();
            try{proofs=new PostgresLoginProofStore(database,policy,proofKey,sql->(binding,now)->{
                // Composition never enables the legacy ACTIVE supplier or positive session enforcement.
                throw new LoginProof.Failure(LoginProof.Error.UNAVAILABLE);
            });}finally{Arrays.fill(proofKey,(byte)0);}
            var lookup=new PostgresCodeIntentKeyLookupStore(database,policy,configuration.scopes(),stage.retentionKeys());
            var confirmations=new PostgresCodeConfirmationStore(database,intents,proofs,policy,migrationCrypto,userCurrent);
            observer=HttpCodeSessionObservations.open(configuration).orElseThrow(()->new IllegalStateException("Code confirmation observer unavailable"));
            var service=new CodeConfirmationService(confirmations,observer);
            var stageRoutes=new CodeIntentRoutes(intents,policy,configuration.actor(),stage.stageCredential(),configuration.scopes());
            var lookupRoutes=new CodeIntentKeyLookupRoutes(lookup,policy,configuration.scopes(),configuration.actor(),configuration.lookupKey());
            var confirmationRoutes=new CodeConfirmationRoutes(service,policy,configuration.scopes(),configuration.actor(),configuration.confirmationKey());
            return Optional.of(new CodeConfirmationResources(cleanup,observer,stageRoutes,lookupRoutes,confirmationRoutes));
        }catch(RuntimeException|Error failure){
            if(cleanup!=null)try{cleanup.close();}catch(RuntimeException|Error cleanupFailure){failure.addSuppressed(cleanupFailure);}
            if(observer!=null)try{observer.close();}catch(RuntimeException|Error closeFailure){failure.addSuppressed(closeFailure);}
            throw failure;
        }
    }
    public void configure(JavalinConfig config){
        if(closed.get())throw new IllegalStateException("Code confirmation resources closed");
        stageRoutes.configure(config);lookupRoutes.configure(config);confirmationRoutes.configure(config);
    }
    public synchronized void start(){
        if(closed.get())throw new IllegalStateException("Code confirmation resources closed");
        cleanup.start();
    }
    @Override public synchronized void close(){
        if(!closed.compareAndSet(false,true))return;
        Throwable primary=null;
        try{cleanup.close();}catch(RuntimeException|Error failure){primary=failure;}
        try{observer.close();}catch(RuntimeException|Error failure){if(primary==null)primary=failure;else primary.addSuppressed(failure);}
        if(primary instanceof RuntimeException failure)throw failure;
        if(primary instanceof Error failure)throw failure;
    }
    @Override public String toString(){return "CodeConfirmationResources[redacted]";}
}
