package com.arman.bank.sso;

import org.keycloak.models.KeycloakSession;
import org.keycloak.protocol.oidc.AccessTokenIntrospectionProviderFactory;
import org.keycloak.protocol.oidc.TokenIntrospectionProvider;

public final class CodeIntentIntrospectionFactory extends AccessTokenIntrospectionProviderFactory {
    @Override public TokenIntrospectionProvider create(KeycloakSession session){return new CodeIntentIntrospection(session,CodeIntentLoginProtocolFactory.owner(session).configuration());}
    @Override public int order(){return 1000;}
}
