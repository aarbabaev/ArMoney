package com.arman.bank.sso;

import org.keycloak.models.KeycloakSession;
import org.keycloak.services.Urls;
import org.keycloak.services.clientpolicy.*;
import org.keycloak.services.clientpolicy.context.TokenResponseContext;
import org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProvider;
import org.keycloak.representations.idm.ClientPolicyExecutorConfigurationRepresentation;
import java.time.*;

/** No SPI factory registration. Only the validated authorization-code response context can mint claims. */
final class ProofTokenPolicy implements ClientPolicyExecutorProvider<ClientPolicyExecutorConfigurationRepresentation> {
    private final KeycloakSession session;
    private final ProofClient client;
    private final CodeKeys keys;
    private final Clock clock;
    private final boolean enabled;
    ProofTokenPolicy(KeycloakSession session, ProofClient client, CodeKeys keys, Clock clock, boolean enabled) {
        this.session=session; this.client=client; this.keys=keys; this.clock=clock; this.enabled=enabled;
    }
    @Override public String getProviderId() { return "armoney-immutable-login-proof"; }
    @Override public void executeOnEvent(ClientPolicyContext event) throws ClientPolicyException {
        if (!enabled || !(event instanceof TokenResponseContext response)) return;
        try {
            var parsed=response.getParseResult();
            if (parsed == null || parsed.isIllegalCode() || parsed.isExpiredCode() || parsed.getCodeData() == null) throw new ProofClient.Failure();
            var data=parsed.getCodeData(); var clientSession=response.getClientSession(); var user=clientSession.getUserSession();
            var builder=response.getAccessTokenResponseBuilder();
            TokenLifetime.configured(user.getRealm(),clientSession.getClient());
            TokenLifetime.actual(builder.getAccessToken(),clock.instant());
            if (!data.getUserSessionId().equals(user.getId()) || !data.getClientUUID().equals(clientSession.getClient().getId())) throw new ProofClient.Failure();
            var context=session.getContext();
            var binding=new LoginProof.Binding(Urls.realmIssuer(context.getUri().getBaseUri(),context.getRealm().getName()),user.getUser().getId(),
                    clientSession.getClient().getClientId(),user.getId());
            var proof=client.resolve(keys,binding,data.getId(),Instant.ofEpochSecond(data.getExpiration()),clock.instant());
            ProofClaims.put(builder.getAccessToken().getOtherClaims(),proof);
            // Do not let a future provider change accidentally persist proof claims in a refresh token.
            if (builder.getRefreshToken()!=null) ProofClaims.clear(builder.getRefreshToken().getOtherClaims());
        } catch (Exception failure) { throw new ClientPolicyException("temporarily_unavailable","Login proof unavailable",jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE); }
    }
}
