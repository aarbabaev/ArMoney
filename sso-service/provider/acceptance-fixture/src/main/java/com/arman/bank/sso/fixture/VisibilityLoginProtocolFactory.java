package com.arman.bank.sso.fixture;

import org.keycloak.models.KeycloakSession;
import org.keycloak.protocol.LoginProtocol;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.OIDCLoginProtocolFactory;

/** Register only in the disposable visibility fixture image, never production. */
public final class VisibilityLoginProtocolFactory extends OIDCLoginProtocolFactory {
    @Override public LoginProtocol create(KeycloakSession session) {
        OIDCLoginProtocol ordinary=(OIDCLoginProtocol)super.create(session);
        return new VisibilityLoginProtocol(ordinary.getConfig()).setSession(session);
    }
    @Override public int order() { return 1000; }
}
