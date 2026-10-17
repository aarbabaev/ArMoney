package com.arman.bank.sso;

import jakarta.ws.rs.core.Response;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.OIDCProviderConfig;
import org.keycloak.protocol.oidc.utils.OIDCRedirectUriBuilder;
import org.keycloak.sessions.AuthenticationSessionModel;

/** Disposable code-intent image only: stage the genuine code for an explicitly armed flow. */
public final class CodeIntentFixtureLoginProtocol extends OIDCLoginProtocol {
    public CodeIntentFixtureLoginProtocol(OIDCProviderConfig config) { super(config); }

    @Override public Response buildRedirectUri(OIDCRedirectUriBuilder builder, AuthenticationSessionModel auth,
                                               UserSessionModel user, ClientSessionContext context) {
        try {
            if (CodeIntentFixtureSupport.handles(auth)) {
                return CodeIntentFixtureSupport.generated(session, builder, auth, user, context);
            }
        } catch (RuntimeException unavailable) {
            // An unavailable fixture must never fall through to an ordinary OIDC redirect.
        }
        return Response.status(503).type("application/json").header("Cache-Control", "no-store").build();
    }
}
