package com.arman.bank.sso;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.keycloak.models.*;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.OIDCProviderConfig;
import org.keycloak.protocol.oidc.utils.*;
import org.keycloak.services.Urls;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.util.JsonSerialization;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Stages only an actual generated BANK_NATIVE code, before returning its redirect. */
final class CodeIntentLoginProtocol extends OIDCLoginProtocol {
    private final CodeIntentLoginProtocolFactory owner;
    private final Clock clock;
    private final java.util.function.BiFunction<String,AuthenticationSessionModel,String> binding;
    CodeIntentLoginProtocol(OIDCProviderConfig config,CodeIntentLoginProtocolFactory owner,Clock clock){this(config,owner,clock,SessionBinding::of);}
    CodeIntentLoginProtocol(OIDCProviderConfig config,CodeIntentLoginProtocolFactory owner,Clock clock,java.util.function.BiFunction<String,AuthenticationSessionModel,String> binding){super(config);this.owner=owner;this.clock=clock;this.binding=binding;}
    private String issuer(){return Urls.realmIssuer(uriInfo.getBaseUri(),realm.getName());}
    private boolean selected(AuthenticationSessionModel auth){return owner.configuration().purposeClient(issuer(),auth.getClient().getClientId());}
    static void requireCode(String responseType,String responseMode){
        if(!"code".equals(responseType)||responseMode!=null&&!"query".equals(responseMode))throw unavailable();
    }
    static void requirePublicCode(ClientModel client,String challenge,String method){
        if(!client.isPublicClient()||!client.isStandardFlowEnabled()||client.isImplicitFlowEnabled()||client.isDirectAccessGrantsEnabled()
                ||!"S256".equals(method)||challenge==null||!challenge.matches("[A-Za-z0-9_-]{43}"))throw unavailable();
    }
    @Override public Response authenticated(AuthenticationSessionModel auth,UserSessionModel user,ClientSessionContext context){
        if(selected(auth))requireCode(auth.getClientNote(RESPONSE_TYPE_PARAM),auth.getClientNote(RESPONSE_MODE_PARAM));
        return super.authenticated(auth,user,context);
    }
    @Override public Response buildRedirectUri(OIDCRedirectUriBuilder builder,AuthenticationSessionModel auth,UserSessionModel user,ClientSessionContext context){
        if(!selected(auth))return super.buildRedirectUri(builder,auth,user,context);
        Response response=null;
        try(InvocationDeadline deadline=InvocationDeadline.start()){
            requireCode(auth.getClientNote(RESPONSE_TYPE_PARAM),auth.getClientNote(RESPONSE_MODE_PARAM));
            response=builder.build();String id=codeId(response,user.getId(),auth.getClient().getId());
            Map<String,String> stored=session.singleUseObjects().get(OAuth2CodeParser.CACHE_KEY_PREFIX+id);
            if(stored==null)throw unavailable();OAuth2Code data=OAuth2Code.deserializeCode(stored);
            if(!id.equals(data.getId())||!user.getId().equals(data.getUserSessionId())||!auth.getClient().getId().equals(data.getClientUUID())
                    ||!context.getClientSession().getClient().getId().equals(data.getClientUUID()))throw unavailable();
            requirePublicCode(auth.getClient(),data.getCodeChallenge(),data.getCodeChallengeMethod());
            var purpose=owner.configuration().purpose(issuer(),auth.getClient().getClientId(),data.getRedirectUriParam());
            // Migration proofs and explicitly legacy callbacks intentionally have no banking proof.
            if(purpose!=CodeIntentProviderConfiguration.Purpose.BANK_NATIVE){deadline.requireOpen();return response;}
            TokenLifetime.configured(user.getRealm(),auth.getClient());
            if(user.getRealm().getAccessCodeLifespan()<1||user.getRealm().getAccessCodeLifespan()>120)throw unavailable();
            Instant now=Instant.ofEpochSecond(clock.instant().getEpochSecond()),expiry=Instant.ofEpochSecond(data.getExpiration());
            if(!now.isBefore(expiry))throw unavailable();
            var binding=new LoginProof.Binding(issuer(),user.getUser().getId(),auth.getClient().getClientId(),user.getId());
            String originalSession=this.binding.apply(realm.getId(),auth),note="armoney.code-intent."+id;
            var resources=owner.resources();String pending=auth.getAuthNote(note);LoginProof.Write write;
            if(pending!=null){
                if(pending.length()>12288)throw unavailable();write=LoginProof.write(Bridge.strictJson(pending.getBytes(StandardCharsets.UTF_8)));
                var key=resources.keys().keys().stream().filter(k->k.id().equals(write.keyId())).findFirst().orElseThrow(CodeIntentLoginProtocol::unavailable);
                if(!binding.equals(write.binding())||!write.digest().equals(resources.keys().digest(key,binding,id))||!expiry.equals(write.expires())
                        ||!originalSession.equals(write.authenticationSession())||!"interactive".equals(write.kind()))throw unavailable();
            }else{
                String event=auth.getAuthNote(PasswordProofFactory.NOTE),password=auth.getAuthNote(PasswordProofFactory.TIME_NOTE);
                if(event==null||password==null)throw unavailable(); // Creation/cookie reuse is never a password event.
                var key=resources.keys().keys().getFirst();
                write=new LoginProof.Write(UUID.randomUUID(),resources.keys().digest(key,binding,id),key.id(),binding,originalSession,now,expiry,"interactive",LoginProof.uuid(event),LoginProof.time(password));
                auth.setAuthNote(note,JsonSerialization.writeValueAsString(write.fields()));
            }
            resources.client().stage(write,deadline);deadline.requireOpen();return response;
        }catch(Exception rejected){if(response!=null)response.close();throw unavailable();}
    }
    static String codeId(Response response,String sessionId,String clientInternalId){
        if(response.getStatus()!=302||response.getLocation()==null||response.getLocation().getRawQuery()==null||response.getLocation().getRawFragment()!=null)throw unavailable();
        var codes=new ArrayList<String>();
        for(String part:response.getLocation().getRawQuery().split("&",-1)){
            String[] pair=part.split("=",2);if("code".equals(URLDecoder.decode(pair[0],StandardCharsets.UTF_8))){if(pair.length!=2)throw unavailable();codes.add(URLDecoder.decode(pair[1],StandardCharsets.UTF_8));}
        }
        if(codes.size()!=1)throw unavailable();String[] pieces=codes.getFirst().split("\\.",-1);
        if(pieces.length!=3||!pieces[1].equals(sessionId)||!pieces[2].equals(clientInternalId))throw unavailable();return LoginProof.uuid(pieces[0]).toString();
    }
    private static WebApplicationException unavailable(){return new WebApplicationException(Response.status(503).type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE).header("Cache-Control","no-store").build());}
}
