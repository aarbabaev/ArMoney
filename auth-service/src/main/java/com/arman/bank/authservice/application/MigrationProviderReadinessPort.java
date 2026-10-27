package com.arman.bank.authservice.application;
import com.arman.bank.authservice.domain.CodeConfirmation;
import com.arman.bank.authservice.domain.LoginProof;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
/** Live provider observation, never an Auth contact-version or migration authority. */
@FunctionalInterface public interface MigrationProviderReadinessPort {
    enum Purpose { MIGRATION_ACCOUNT, MIGRATION_MANAGED }
    record Request(Purpose purpose,String originalRedirectUri,LoginProof.Binding binding) {
        public Request { Objects.requireNonNull(purpose);Objects.requireNonNull(originalRedirectUri);Objects.requireNonNull(binding); }
        @Override public String toString(){return "MigrationReadinessRequest[redacted]";}
    }
    record Observation(Request request,CodeConfirmation.Facts facts,boolean providerEmailVerified) {
        public Observation { Objects.requireNonNull(request);Objects.requireNonNull(facts); }
        public CodeConfirmation.Facts requireReady(Request expected){
            if(!request.equals(expected)||!expected.binding().equals(facts.binding())||!providerEmailVerified)throw CodeConfirmation.unavailable();
            return facts;
        }
        @Override public String toString(){return "MigrationReadinessObservation[redacted]";}
    }
    Observation observe(Request request,Instant originalDatabaseStart,Duration remainingBudget);
    static MigrationProviderReadinessPort unavailable(){return (request,start,budget)->{throw CodeConfirmation.unavailable();};}
}
