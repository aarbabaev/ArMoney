package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.CodeIntentKeyLookup;
import java.util.function.Function;

/** Caller-authenticated operation scope; no HTTP or provider transport is installed. */
public interface CodeIntentKeyLookupStore {
    <T>T inRequest(String authenticatedActor,Function<Request,T> operation);
    default CodeIntentKeyLookup.Result lookup(String authenticatedActor,CodeIntentKeyLookup.Input input){
        return inRequest(authenticatedActor,request->request.lookup(input));
    }
    interface Request { CodeIntentKeyLookup.Result lookup(CodeIntentKeyLookup.Input input); }
}
