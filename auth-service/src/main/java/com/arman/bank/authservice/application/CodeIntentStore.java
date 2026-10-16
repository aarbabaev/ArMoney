package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.CodeIntent;
import com.arman.bank.authservice.domain.LoginProof;

/** Internal only. No HTTP adapter, code redemption, proof creation or positive eligibility. */
public interface CodeIntentStore {
    CodeIntent.Snapshot stage(String logicalActor, LoginProof.Write write);
    int cleanup();
}
