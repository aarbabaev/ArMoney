package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.ManagedBankingAccess.*;
import com.arman.bank.authservice.domain.LoginProof;
import com.arman.bank.authservice.domain.CodeConfirmation;
import java.time.Instant;
import java.util.function.Function;

/** One request owns the same PRIMARY connection across admission, remote I/O and completion. */
public interface ManagedBankingStore {
    <T>T inRequest(Function<Request,T> action);
    interface Admission { LoginProof.Binding binding(); Instant startedAt(); }
    interface Request {
        Admission admitExchange(Token token);
        Admission admitValidation(String opaqueDigest);
        Decision exchange(Admission admission,CodeConfirmation.Facts facts,String opaqueDigest);
        Decision validate(Admission admission,CodeConfirmation.Facts facts);
    }
    void revoke(String opaqueDigest);
}
