package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.LoginProof.*;
import com.arman.bank.authservice.domain.LoginProof.Error;
import java.time.Instant;

/** Auth-local storage only. HTTP enters inRequest before parsing, including malformed requests
 * and exact retries. Scoped command methods share that operation's single admission charge. */
public interface LoginProofStore {
    /** Authenticated caller scope must enclose parsing, admission, remote work and persistence. */
    <T> T inRequest(String authenticatedWriter, java.util.function.Function<Request,T> operation);
    interface Request {
        Snapshot write(Write command);
        Snapshot resolve(Resolve lookup);
        Admission admitCreate(Write command);
        Snapshot writeObserved(Write command, Admission admission, Observation observation);
    }
    /** Standalone admission-only operation; do not pair it with a separately scoped command. */
    void admit(String authenticatedWriter);
    Snapshot write(String authenticatedWriter, Write command);
    /** Staged CREATE metadata. Only Request.admitCreate issues a live request-bound admission. */
    interface Admission { Instant startedAt(); Binding binding(); }
    /** Unscoped staged operations cannot safely span remote work; retained fail-closed compatibility. */
    default Admission admitCreate(String writer,Write command){throw new Failure(Error.UNAVAILABLE);}
    default Snapshot writeObserved(String writer,Write command,Admission admission,Observation observation){throw new Failure(Error.UNAVAILABLE);}
    Snapshot resolve(String authenticatedWriter, Resolve lookup);

    /** Factory binds this gate to the SAME owning transaction; no remote I/O is allowed.
     * Caller must already hold immutable request-local provider evidence obtained before locks.
     * This slice supplies only an unavailable production adapter. */
    @FunctionalInterface interface EligibilityPort {
        Activation requireActive(Binding binding, Instant databaseNow);
    }
}
