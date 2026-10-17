package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.CodeConfirmation;
import com.arman.bank.authservice.domain.LoginProof;
import java.time.Instant;

/** Trusted exact postcommit BOTH-session facts. No production transport/factory exists here. */
@FunctionalInterface public interface CodeConfirmationObservations {
    CodeConfirmation.Facts observe(LoginProof.Binding binding,Instant originalDatabaseStart);
}
