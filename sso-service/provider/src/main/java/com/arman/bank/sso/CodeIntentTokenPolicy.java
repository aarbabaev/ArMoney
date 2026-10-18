package com.arman.bank.sso;

import org.keycloak.models.KeycloakSession;
import org.keycloak.services.Urls;
import org.keycloak.services.clientpolicy.*;
import org.keycloak.services.clientpolicy.context.TokenResponseContext;
import org.keycloak.services.clientpolicy.executor.ClientPolicyExecutorProvider;
import org.keycloak.representations.idm.ClientPolicyExecutorConfigurationRepresentation;
import java.time.*;

/** Only this post-code-validation executor can create banking proof claims. */
final class CodeIntentTokenPolicy implements ClientPolicyExecutorProvider<ClientPolicyExecutorConfigurationRepresentation> {
    static final String ID="armoney-code-intent-confirmation";
    private final KeycloakSession session;
    private final Clock clock;
    CodeIntentTokenPolicy(KeycloakSession session,Clock clock){this.session=session;this.clock=clock;}
    @Override public String getProviderId(){return ID;}
    @Override public void executeOnEvent(ClientPolicyContext event)throws ClientPolicyException{
        if(!(event instanceof TokenResponseContext response))return; // Never mint on refresh, exchange or direct grants.
        try(InvocationDeadline deadline=InvocationDeadline.start()){
            var context=session.getContext();var owner=CodeIntentLoginProtocolFactory.owner(session);var config=owner.configuration();
            String issuer=Urls.realmIssuer(context.getUri().getBaseUri(),context.getRealm().getName());
            if(!config.purposeClient(issuer,context.getClient().getClientId()))return;
            var builder=response.getAccessTokenResponseBuilder();
            ProofClaims.clear(builder.getAccessToken().getOtherClaims());
            if(builder.getRefreshToken()!=null)ProofClaims.clear(builder.getRefreshToken().getOtherClaims());
            if(builder.getIdToken()!=null)ProofClaims.clear(builder.getIdToken().getOtherClaims());
            var parsed=response.getParseResult();
            if(parsed==null||parsed.isIllegalCode()||parsed.isExpiredCode()||parsed.getCodeData()==null)throw new IllegalStateException();
            var data=parsed.getCodeData();var client=response.getClientSession();var user=client.getUserSession();
            if(!data.getUserSessionId().equals(user.getId())||!data.getClientUUID().equals(client.getClient().getId())
                    ||!client.getClient().getId().equals(context.getClient().getId()))throw new IllegalStateException();
            CodeIntentLoginProtocol.requirePublicCode(client.getClient(),data.getCodeChallenge(),data.getCodeChallengeMethod());
            // Consumed code data retains the original redirect; present client-session notes have no authority.
            var purpose=config.purpose(issuer,client.getClient().getClientId(),data.getRedirectUriParam());
            if(purpose!=CodeIntentProviderConfiguration.Purpose.BANK_NATIVE){deadline.requireOpen();return;}
            TokenLifetime.configured(user.getRealm(),client.getClient());TokenLifetime.actual(builder.getAccessToken(),clock.instant());
            var binding=new LoginProof.Binding(issuer,user.getUser().getId(),client.getClient().getClientId(),user.getId());
            var resources=owner.resources();var proof=resources.client().lookupAndConfirm(resources.keys(),binding,data.getId(),Instant.ofEpochSecond(data.getExpiration()),deadline);
            deadline.requireOpen();ProofClaims.put(builder.getAccessToken().getOtherClaims(),proof);
        }catch(Exception rejected){throw new ClientPolicyException("temporarily_unavailable","Code confirmation unavailable",jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE);}
    }
}
