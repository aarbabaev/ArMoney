package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.*;
import com.arman.bank.runtime.Database;
import java.time.Clock;
import java.util.*;

/** Explicit resource graph only. Main registration and genuine consumer readiness remain separate. */
public final class LegacyMigrationResources implements AutoCloseable {
    public interface OwnedObservations extends MigrationManagedProofPort.Observations,AutoCloseable { @Override void close(); }
    @FunctionalInterface public interface ObserverFactory { OwnedObservations open(LegacyMigrationConfiguration.ObservationSettings settings); }
    private final List<AutoCloseable> owned;
    private final LegacyMigrationDeliveryWorker worker;
    private final LegacyMigrationBrowserRoutes routes;
    private final LegacyMigrationFinalizationRoutes decisionRoutes;
    private final LegacyMigrationOtpService otp;
    private boolean closed,started;
    private LegacyMigrationResources(List<AutoCloseable> owned,LegacyMigrationDeliveryWorker worker,LegacyMigrationBrowserRoutes routes,LegacyMigrationOtpService otp,LegacyMigrationFinalizationRoutes decisionRoutes){this.owned=List.copyOf(owned);this.worker=worker;this.routes=routes;this.otp=otp;this.decisionRoutes=decisionRoutes;}
    public static Optional<LegacyMigrationResources> open(Database database,AuthService auth,LegacyMigrationConfiguration c){
        return open(database,auth,c,settings->{
            var adapter=HttpCodeSessionObservations.openMigration(settings.validated());
            return new OwnedObservations(){
                public com.arman.bank.authservice.domain.CodeConfirmation.Facts observe(com.arman.bank.authservice.domain.LoginProof.Binding binding,java.time.Instant start,java.time.Duration remaining){return adapter.observe(binding,start,remaining);}
                public void close(){adapter.close();}
            };
        });
    }
    public static Optional<LegacyMigrationResources> open(Database database,AuthService auth,LegacyMigrationConfiguration c,ObserverFactory factory){
        Objects.requireNonNull(c);if(!c.enabled)return Optional.empty();
        Objects.requireNonNull(database);Objects.requireNonNull(auth);Objects.requireNonNull(factory);
        var owned=new ArrayList<AutoCloseable>();
        try{
            var custody=new LegacyMigrationCrypto(c.encryptionId,c.encryption(),c.digestId,c.digests());
            var proof=new LegacyMigrationManagedProofCrypto(c.proofId,c.proof());
            var crypto=new LegacyMigrationOtpCrypto(c.otpId,c.otpHmac(),c.otpEncryption());
            var observer=Objects.requireNonNull(factory.open(c.observation));owned.add(observer);
            var accountProvider=new LegacyMigrationLegacyAccountProviderClient(c.account,Clock.systemUTC(),observer);owned.add(accountProvider);
            var managedProvider=new LegacyMigrationManagedProviderClient(c.managed,Clock.systemUTC(),observer);owned.add(managedProvider);
            var user=new HttpLegacyMigrationStage(c.userOrigin,c.readKey,c.stageKey);owned.add(user);
            var transport=new VerificationDeliveryTransport(c.email,c.smsOrigin,c.smsKey);owned.add(transport::close);
            var local=new PostgresLegacyMigrationStore(database,custody,true);
            var as=new PostgresLegacyMigrationManagedProofStore.Settings(c.actor,c.account.issuer().toString(),c.account.clientId(),c.account.redirect().toString());
            var ms=new PostgresLegacyMigrationManagedProofStore.Settings(c.actor,c.managed.issuer().toString(),c.managed.clientId(),c.managed.redirect().toString());
            var account=new PostgresLegacyMigrationAccountProofStore(database,custody,proof,accountProvider,as,true);
            var managed=new PostgresLegacyMigrationManagedProofStore(database,custody,proof,managedProvider,observer,ms,true);
            var store=new PostgresLegacyMigrationOtpStore(database,custody,crypto,c.actor,true,managed);
            var otp=new LegacyMigrationOtpService(store,user);
            var journal=new PostgresLegacyMigrationFinalizationStore(database,store,managed,proof,c.actor,true);
            var consumer=new PostgresMigrationBankingEligibilityStore(proof);
            var publication=new PostgresMigrationFinalAckStore(database,custody,proof,journal,store,managed,consumer,c.actor,c.publicationKey);owned.add(publication);
            var continuation=new PostgresLegacyMigrationBrowserService.ContinuationServices(store,user,journal,publication,consumer,custody);
            var service=new PostgresLegacyMigrationBrowserService(database,auth,local,account,managed,proof,c.browser.legacyAuthorizationEndpoint(),c.browser.managedAuthorizationEndpoint(),true,continuation);
            var decisionRoutes=new LegacyMigrationFinalizationRoutes(journal,c.decisionKey,true);
            var worker=new LegacyMigrationDeliveryWorker(store,crypto,new LegacyMigrationDeliveryTransport(transport),Clock.systemUTC());owned.add(worker);
            return Optional.of(new LegacyMigrationResources(owned,worker,new LegacyMigrationBrowserRoutes(c.browser,c.ingress,service),otp,decisionRoutes));
        }catch(RuntimeException|Error failure){constructionFailed(owned,failure);throw failure;}
    }
    public synchronized LegacyMigrationBrowserRoutes routes(){requireOpen();return routes;}
    public synchronized LegacyMigrationOtpService otp(){requireOpen();return otp;}
    public synchronized LegacyMigrationFinalizationRoutes decisionRoutes(){requireOpen();return decisionRoutes;}
    /** Caller must invoke only after successful HTTP startup; does not authorize public activation. */
    public synchronized void startAfterHttpStartup(){requireOpen();if(started)throw new IllegalStateException("Migration resources already started");worker.start();started=true;}
    private void requireOpen(){if(closed)throw new IllegalStateException("Migration resources closed");}
    @Override public synchronized void close(){if(closed)return;closed=true;RuntimeException failure=new IllegalStateException("Migration resource cleanup failed");if(closeAll(owned,failure))throw failure;}
    /** Technical lifecycle seam: no business authority, same reverse cleanup used on partial construction. */
    static void constructionFailed(List<AutoCloseable> resources,Throwable primary){closeAll(resources,primary);}
    private static boolean closeAll(List<AutoCloseable> resources,Throwable primary){boolean failed=false;for(int i=resources.size()-1;i>=0;i--)try{resources.get(i).close();}catch(Exception|LinkageError cleanup){failed=true;primary.addSuppressed(cleanup);}return failed;}
    @Override public String toString(){return "LegacyMigrationResources[redacted]";}
}
