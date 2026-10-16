package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.LoginProof.*;
import com.arman.bank.authservice.domain.LoginProof.Error;
import java.time.Instant;

/** Auth-local storage only. HTTP must admit every authenticated request before parsing,
 * including malformed requests and exact retries. Command methods do not double-charge admission. */
public interface LoginProofStore {
    void admit(String authenticatedWriter);
    Snapshot write(String authenticatedWriter, Write command);
    /** Staged CREATE use case only. Existing production routes do not call this seam. */
    interface Admission { Instant startedAt(); Binding binding(); }
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
