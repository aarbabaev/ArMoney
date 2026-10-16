package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.CodeIntent;
import com.arman.bank.authservice.domain.LoginProof;

/** Internal only. No HTTP adapter, code redemption, proof creation or positive eligibility. */
public interface CodeIntentStore {
    <T> T inRequest(String logicalActor, java.util.function.Function<Request,T> operation);
    interface Request { CodeIntent.Snapshot stage(LoginProof.Write write); }
    CodeIntent.Snapshot stage(String logicalActor, LoginProof.Write write);
    int cleanup();
}
