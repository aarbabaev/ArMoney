package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.LoginProof.*;
import java.time.Instant;

/** Trusted staged port only. No production provider DTO/endpoint or implementation is registered. */
@FunctionalInterface
public interface LoginProofObservations {
    Observation observe(Binding binding,Instant admittedAt);
}
