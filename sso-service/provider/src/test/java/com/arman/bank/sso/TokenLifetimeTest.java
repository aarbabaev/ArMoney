package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import org.keycloak.models.*;
import org.keycloak.representations.AccessToken;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

final class TokenLifetimeTest {
    @Test void clientOverrideCannotExtendRealm900OrUseSessionLifetime() {
        for(String override:new String[]{"3600","-1","0","901","not-an-int"," 600 "})
            assertThrows(ProofClient.Failure.class,()->TokenLifetime.configured(realm(900),client(override)));
        for(String override:new String[]{null,""," ","1","600","900"})
            assertDoesNotThrow(()->TokenLifetime.configured(realm(900),client(override)));
        assertThrows(ProofClient.Failure.class,()->TokenLifetime.configured(realm(3600),client(null)));
        assertDoesNotThrow(()->TokenLifetime.configured(realm(3600),client("600")));
    }
    @Test void actualIssuedTokenMustHavePositiveBoundedUnexpiredIatExp() {
        Instant now=Instant.ofEpochSecond(2000);
        assertDoesNotThrow(()->TokenLifetime.actual(token(2000L,2900L),now));
        for(AccessToken token:new AccessToken[]{token(null,2900L),token(2000L,null),token(2000L,5600L),
                token(2000L,2000L),token(1000L,1900L),token(2031L,2900L),token(-1L,2001L),token(2000L,Long.MAX_VALUE)})
            assertThrows(ProofClient.Failure.class,()->TokenLifetime.actual(token,now));
    }
    static AccessToken token(Long issued,Long expiry){var token=new AccessToken();token.iat(issued);token.exp(expiry);return token;}
    static RealmModel realm(int seconds){return ProofHookTest.proxy(RealmModel.class,(name,args)->name.equals("getAccessTokenLifespan")?seconds:ProofHookTest.unexpected(name));}
    static ClientModel client(String value){return ProofHookTest.proxy(ClientModel.class,(name,args)->name.equals("getAttribute")?value:ProofHookTest.unexpected(name));}
}
