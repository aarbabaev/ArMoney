package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.CodeConfirmation;
import com.arman.bank.authservice.domain.LoginProof;
import java.util.Objects;

/** Same request/connection across committed admission, remote observation and final transaction. */
public final class CodeConfirmationService {
    private final CodeConfirmationStore store;
    private final CodeConfirmationObservations observations;
    public CodeConfirmationService(CodeConfirmationStore store,CodeConfirmationObservations observations){this.store=Objects.requireNonNull(store);this.observations=Objects.requireNonNull(observations);}
    public CodeConfirmation.Outcome confirm(String actor,LoginProof.Resolve code){
        return store.inRequest(actor,request->{
            var admission=request.admit(code);
            var facts=observations.observe(admission.binding(),admission.startedAt());
            // A committed denial is a VALUE; a future HTTP adapter converts it only after this returns.
            return request.complete(admission,facts);
        });
    }
}
