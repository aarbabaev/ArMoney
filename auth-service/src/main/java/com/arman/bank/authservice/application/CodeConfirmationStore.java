package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.CodeConfirmation;
import com.arman.bank.authservice.domain.LoginProof;
import java.time.Instant;
import java.util.function.Function;

public interface CodeConfirmationStore {
    <T>T inRequest(String authenticatedActor,Function<Request,T> operation);
    interface Admission { LoginProof.Binding binding(); Instant startedAt(); }
    interface Request {
        /** Commits creator-only durable anchor before returning; never reopens unresolved FIRST. */
        Admission admit(LoginProof.Resolve code);
        CodeConfirmation.Outcome complete(Admission admission,CodeConfirmation.Facts facts);
    }
}
