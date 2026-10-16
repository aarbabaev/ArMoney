package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.IdentityVerification;
import com.arman.bank.authservice.domain.IdentityVerification.*;
import com.arman.bank.authservice.domain.Verification;
import com.arman.bank.authservice.domain.VerificationProjection;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.function.BiFunction;
import java.time.Instant;

/** Remote observation always precedes the owning local transaction; no runtime wiring here. */
public final class IdentityVerificationService {
    private final IdentityVerificationStore store;
    private final IdentityVerificationStore.Observations observations;
    private final Policy policy;
    private final Semaphore inFlight=new Semaphore(16);
    public IdentityVerificationService(IdentityVerificationStore store,IdentityVerificationStore.Observations observations,Policy policy){this.store=Objects.requireNonNull(store);this.observations=Objects.requireNonNull(observations);this.policy=Objects.requireNonNull(policy);}
    private <T>T observed(String writer,Flow flow,BiFunction<Observation,Instant,T> operation){return observed(writer,flow,operation,false);}
    private <T>T observed(String writer,Flow flow,BiFunction<Observation,Instant,T> operation,boolean status){return bounded(()->{
        IdentityVerification.validate(flow,policy);if(!policy.writers().contains(writer))throw new Verification.Failure(Verification.Error.BINDING);
        Instant startedAt=store.admit(writer,flow);if(startedAt==null)throw new Verification.Failure(Verification.Error.UNAVAILABLE);
        Observation result=status?observations.observeStatus(flow,startedAt):observations.observe(flow,startedAt);
        if(result==null||!startedAt.equals(result.observationStartedAt()))throw new Verification.Failure(Verification.Error.UNAVAILABLE);
        return operation.apply(result,startedAt);
    });}
    public Verification.Result enroll(String writer,Enrollment command){if(command==null||command.commandId()==null)bad();return observed(writer,command.flow(),(evidence,started)->accepted(store.enroll(writer,command,evidence,started)));}
    public Verification.Result status(String writer,UUID enrollment,Flow flow){if(enrollment==null)bad();return observed(writer,flow,(evidence,started)->accepted(store.status(writer,enrollment,flow,evidence,started)),true);}
    public Verification.Result issue(String writer,Issue command){if(command==null)bad();validate(command.commandId(),command.enrollmentId(),command.purpose(),command.contactVersion());return observed(writer,command.flow(),(evidence,started)->accepted(store.issue(writer,command,evidence,started)));}
    public Verification.Result consume(String writer,Consume command){if(command==null||command.challengeId()==null||command.code()==null||!command.code().matches("[0-9]{6}"))bad();validate(command.commandId(),command.enrollmentId(),command.purpose(),command.contactVersion());return observed(writer,command.flow(),(evidence,started)->accepted(store.consume(writer,command,evidence,started)));}
    public VerificationProjection.Status prepare(String writer,UUID command,UUID enrollment,Flow flow){if(command==null||enrollment==null)bad();return observed(writer,flow,(evidence,started)->store.prepare(writer,command,enrollment,flow,evidence,started));}
    private <T>T bounded(Supplier<T> operation){if(!inFlight.tryAcquire())throw new Verification.Failure(Verification.Error.RATE_LIMITED);try{return operation.get();}finally{inFlight.release();}}
    private static void validate(UUID command,UUID enrollment,Verification.Purpose purpose,int version){if(command==null||enrollment==null||purpose==null||version<1)bad();}
    private static Verification.Result accepted(Verification.Result result){if(result==null)throw new Verification.Failure(Verification.Error.UNAVAILABLE);if(result.error()!=null)throw new Verification.Failure(result.error());return result;}
    private static void bad(){throw new Verification.Failure(Verification.Error.BAD_INPUT);}
}
