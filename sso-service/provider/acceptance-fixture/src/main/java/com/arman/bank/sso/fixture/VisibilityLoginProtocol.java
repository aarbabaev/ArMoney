package com.arman.bank.sso.fixture;

import jakarta.ws.rs.core.Response;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.OIDCProviderConfig;
import org.keycloak.protocol.oidc.utils.OIDCRedirectUriBuilder;
import org.keycloak.sessions.AuthenticationSessionModel;
import java.util.concurrent.TimeUnit;

/** Disposable image only: pauses the real OIDC redirect after user/client attachment. */
public final class VisibilityLoginProtocol extends OIDCLoginProtocol {
    public VisibilityLoginProtocol(OIDCProviderConfig config) { super(config); }
    @Override public Response buildRedirectUri(OIDCRedirectUriBuilder builder,AuthenticationSessionModel auth,
                                               UserSessionModel user,ClientSessionContext context) {
        VisibilityState.Entry held=null;
        try {
            VisibilityState.key();
            if (!"code".equals(auth.getClientNote(RESPONSE_TYPE_PARAM))
                    || auth.getClientNote(RESPONSE_MODE_PARAM)!=null && !"query".equals(auth.getClientNote(RESPONSE_MODE_PARAM)))
                return unavailable();
            held=VisibilityState.hold(new VisibilityState.Tuple(user.getRealm().getId(),user.getUser().getId(),
                    user.getId(),context.getClientSession().getClient().getClientId(),context.getClientSession().getClient().getId()));
            boolean released=held.release.await(15,TimeUnit.SECONDS);
            if (!released || !"RELEASED".equals(held.state) || !"COMPLETE".equals(held.mode)) return unavailable();
            return super.buildRedirectUri(builder,auth,user,context);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return unavailable(); }
        catch (RuntimeException failure) { return unavailable(); }
        finally { if (held!=null) VisibilityState.finish(held); }
    }
    private static Response unavailable() {
        return Response.status(503).type("application/json").header("Cache-Control","no-store").build();
    }
}
