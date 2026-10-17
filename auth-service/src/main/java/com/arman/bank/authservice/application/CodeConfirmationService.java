package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.CodeConfirmation;
import com.arman.bank.authservice.domain.LoginProof;
import java.util.Objects;
import java.util.function.Function;

/** Same request/connection across committed admission, remote observation and final transaction. */
public final class CodeConfirmationService {
    private final CodeConfirmationStore store;
    private final CodeConfirmationObservations observations;
    public CodeConfirmationService(CodeConfirmationStore store,CodeConfirmationObservations observations){this.store=Objects.requireNonNull(store);this.observations=Objects.requireNonNull(observations);}
    public interface Request { CodeConfirmation.Outcome confirm(LoginProof.Resolve code); }
    /** Transport parsing runs inside the existing admission/backend scope, never a nested request. */
    public <T>T inRequest(String actor,Function<Request,T> operation){
        return store.inRequest(actor,owned->{
            var request=new Scoped(owned);
            try{return operation.apply(request);}finally{request.closed=true;}
        });
    }
    public CodeConfirmation.Outcome confirm(String actor,LoginProof.Resolve code){
        return inRequest(actor,request->request.confirm(code));
    }
    private final class Scoped implements Request {
        private final CodeConfirmationStore.Request owned;
        private final Thread owner=Thread.currentThread();
        private boolean used,closed;
        private Scoped(CodeConfirmationStore.Request owned){this.owned=owned;}
        @Override public CodeConfirmation.Outcome confirm(LoginProof.Resolve code){
            if(used||closed||Thread.currentThread()!=owner||Thread.currentThread().isInterrupted())throw CodeConfirmation.unavailable();
            used=true;
            var admission=owned.admit(code);
            // A cancellation arriving after acknowledged admission COMMIT must never start remote work.
            if(Thread.currentThread().isInterrupted())throw CodeConfirmation.unavailable();
            var facts=observations.observe(admission.binding(),admission.startedAt());
            // A committed denial is a VALUE; HTTP converts it only after the store scope returns.
            return owned.complete(admission,facts);
        }
    }
}
