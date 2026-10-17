package com.arman.bank.sso;

import org.keycloak.models.KeycloakSession;
import org.keycloak.protocol.LoginProtocol;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.OIDCLoginProtocolFactory;

/** Register exclusively in the disposable code-intent fixture image. */
public final class CodeIntentFixtureLoginProtocolFactory extends OIDCLoginProtocolFactory {
    @Override public LoginProtocol create(KeycloakSession session) {
        OIDCLoginProtocol ordinary = (OIDCLoginProtocol) super.create(session);
        return new CodeIntentFixtureLoginProtocol(ordinary.getConfig()).setSession(session);
    }
    @Override public int order() { return 1000; }
}
