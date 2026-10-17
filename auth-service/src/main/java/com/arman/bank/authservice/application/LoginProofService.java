package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.LoginProof.*;
import com.arman.bank.authservice.domain.LoginProof.Error;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/** Staged CREATE orchestration. Admission commits before I/O; no route or production factory selects it. */
public final class LoginProofService {
    private final LoginProofStore store;
    private final LoginProofObservations observations;
    private final Semaphore inFlight=new Semaphore(16);
    public LoginProofService(LoginProofStore store,LoginProofObservations observations){
        this.store=Objects.requireNonNull(store);this.observations=Objects.requireNonNull(observations);
    }
    public Snapshot write(String writer,Write command){
        if(!inFlight.tryAcquire())throw new Failure(Error.RATE_LIMITED);
        try{
            var admission=store.admitCreate(writer,command);
            var evidence=observations.observe(admission.binding(),admission.startedAt());
            return store.writeObserved(writer,command,admission,evidence);
        }finally{inFlight.release();}
    }
}
