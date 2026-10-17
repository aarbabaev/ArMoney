package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.LoginProof.*;
import com.arman.bank.authservice.domain.LoginProof.Error;
import java.util.Objects;

/** Staged CREATE orchestration. Admission commits before I/O; no route or production factory selects it. */
public final class LoginProofService {
    private final LoginProofStore store;
    private final LoginProofObservations observations;
    public LoginProofService(LoginProofStore store,LoginProofObservations observations){
        this.store=Objects.requireNonNull(store);this.observations=Objects.requireNonNull(observations);
    }
    public Snapshot write(String writer,Write command){
        return store.inRequest(writer,request->{
            var admission=request.admitCreate(command);
            var evidence=observations.observe(admission.binding(),admission.startedAt());
            return request.writeObserved(command,admission,evidence);
        });
    }
}
