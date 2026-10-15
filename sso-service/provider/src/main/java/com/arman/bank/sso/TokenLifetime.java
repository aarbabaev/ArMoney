package com.arman.bank.sso;

import org.keycloak.models.*;
import org.keycloak.protocol.oidc.OIDCConfigAttributes;
import org.keycloak.representations.AccessToken;
import java.time.Instant;

final class TokenLifetime {
    /** Matches pinned TokenManager precedence; -1 session lifetime is deliberately unsupported. */
    static void configured(RealmModel realm,ClientModel client) {
        String override=client.getAttribute(OIDCConfigAttributes.ACCESS_TOKEN_LIFESPAN);
        int seconds;
        try { seconds=override!=null && !override.trim().isEmpty()?Integer.parseInt(override):realm.getAccessTokenLifespan(); }
        catch(RuntimeException invalid) { throw new ProofClient.Failure(); }
        if(seconds<1 || seconds>900) throw new ProofClient.Failure();
    }
    static void actual(AccessToken token,Instant now) {
        if(token==null || token.getIat()==null || token.getExp()==null) throw new ProofClient.Failure();
        long issued=token.getIat(), expires=token.getExp(), current=now.getEpochSecond();
        if(issued<0 || issued>current+30 || expires<=issued || expires-issued>900 || expires<=current) throw new ProofClient.Failure();
    }
    private TokenLifetime() { }
}
