package com.arman.bank.sso;

import org.keycloak.models.*;
import org.keycloak.protocol.oidc.AccessTokenIntrospectionProvider;
import org.keycloak.representations.AccessToken;

/** Preserves only signed token proof data, never reconstructing it from present session notes. */
final class CodeIntentIntrospection extends AccessTokenIntrospectionProvider<AccessToken> {
    private final CodeIntentProviderConfiguration configuration;
    CodeIntentIntrospection(KeycloakSession session,CodeIntentProviderConfiguration configuration){super(session);this.configuration=configuration;}
    @Override public AccessToken transformAccessToken(AccessToken original,UserSessionModel user){
        AccessToken transformed=super.transformAccessToken(original,user);
        if(transformed!=null&&configuration.bankClient(original.getIssuer(),original.getIssuedFor()))
            ProofClaims.preserve(original.getOtherClaims(),transformed.getOtherClaims());
        return transformed;
    }
}
