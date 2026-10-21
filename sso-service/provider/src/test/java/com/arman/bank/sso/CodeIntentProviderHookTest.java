package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import org.keycloak.models.*;
import org.keycloak.protocol.LoginProtocol;
import org.keycloak.protocol.oidc.TokenManager;
import org.keycloak.protocol.oidc.utils.*;
import org.keycloak.representations.*;
import org.keycloak.services.clientpolicy.ClientPolicyException;
import org.keycloak.services.clientpolicy.context.TokenResponseContext;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.util.JsonSerialization;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.*;
import java.lang.reflect.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual pinned override/context objects with synthetic transport/model data; not runtime PKCE proof. */
class CodeIntentProviderHookTest {
    @Test void generatedNativeCodeWaitsForStageAndUncertainRetryKeepsExactOriginalCommand()throws Exception{
        try(var f=new Fixture()){
            f.failStage=true;
            assertThrows(WebApplicationException.class,()->f.redirect());
            assertEquals(List.of(CodeIntentHttp.Operation.STAGE),f.operations);
            var first=new LinkedHashMap<>(f.requests.getFirst());String note=f.notes.get("armoney.code-intent."+f.codeId);assertNotNull(note);
            f.failStage=false;try(var response=f.redirect()){assertEquals(302,response.getStatus());}
            assertEquals(first,f.requests.getLast());assertEquals(note,f.notes.get("armoney.code-intent."+f.codeId));
        }
    }
    @Test void noPasswordEventOrMismatchedPersistedCodeCannotStageOrReleaseNativeCode(){
        try(var f=new Fixture()){
            f.notes.clear();assertThrows(WebApplicationException.class,()->f.redirect());assertTrue(f.operations.isEmpty());
        }
        try(var f=new Fixture()){
            f.stored=null;assertThrows(WebApplicationException.class,()->f.redirect());assertTrue(f.operations.isEmpty());
        }
    }
    @Test void migrationPurposeComesFromOriginalCodeNotPresentRedirectNotes()throws Exception{
        try(var f=new Fixture()){
            f.data=f.data("https://bank.example/migration/managed/callback");f.stored=f.data.serializeCode();
            try(var response=f.redirect()){assertEquals(302,response.getStatus());}assertTrue(f.operations.isEmpty());
            f.access.getOtherClaims().put(ProofClaims.KIND,"interactive");f.id.getOtherClaims().put(ProofClaims.KIND,"interactive");
            f.policy().executeOnEvent(f.tokenContext());
            assertTrue(f.operations.isEmpty());assertFalse(f.access.getOtherClaims().containsKey(ProofClaims.KIND));assertFalse(f.id.getOtherClaims().containsKey(ProofClaims.KIND));
        }
    }
    @Test void consumedNativeCodeRequiresLookupAndConfirmationBeforeAccessClaimsOnly()throws Exception{
        try(var f=new Fixture()){
            f.refresh.getOtherClaims().put(ProofClaims.KIND,"interactive");f.id.getOtherClaims().put(ProofClaims.KIND,"interactive");
            f.policy().executeOnEvent(f.tokenContext());
            assertEquals(List.of(CodeIntentHttp.Operation.KEY,CodeIntentHttp.Operation.CONFIRM),f.operations);
            assertEquals("interactive",f.access.getOtherClaims().get(ProofClaims.KIND));assertEquals(true,f.access.getOtherClaims().get(ProofClaims.ELIGIBLE));
            assertEquals(f.event,f.access.getOtherClaims().get(ProofClaims.EVENT));assertTrue(f.refresh.getOtherClaims().isEmpty());assertTrue(f.id.getOtherClaims().isEmpty());
        }
    }
    @Test void unconfirmedNativeCodeHasNoClaimsAndNoHistoricalFallback()throws Exception{
        try(var f=new Fixture()){
            f.failConfirmation=true;assertThrows(ClientPolicyException.class,()->f.policy().executeOnEvent(f.tokenContext()));
            assertEquals(List.of(CodeIntentHttp.Operation.KEY,CodeIntentHttp.Operation.CONFIRM),f.operations);assertTrue(f.access.getOtherClaims().isEmpty());
        }
    }
    @Test void dedicatedAccountHooksValidatePurposeWithoutBankingTransportAndClearAllTokenClaims()throws Exception{
        try(var f=new Fixture(true)){
            f.notes.clear();try(var response=f.redirect()){assertEquals(302,response.getStatus());}assertTrue(f.operations.isEmpty());
            for(var token:List.of(f.access,f.refresh,f.id)){token.getOtherClaims().put(ProofClaims.EVENT,f.event);token.getOtherClaims().put(ProofClaims.KIND,"interactive");token.getOtherClaims().put(ProofClaims.ELIGIBLE,true);}
            f.policy().executeOnEvent(f.tokenContext());assertTrue(f.operations.isEmpty());assertTrue(f.access.getOtherClaims().isEmpty());assertTrue(f.refresh.getOtherClaims().isEmpty());assertTrue(f.id.getOtherClaims().isEmpty());
        }
    }
    @Test void dedicatedAccountWrongOriginalCallbackOrPkceCannotBypassEitherHook()throws Exception{
        for(boolean wrongCallback:List.of(true,false))try(var f=new Fixture(true)){
            f.data=wrongCallback?f.data("https://bank.example/other"):f.data("https://bank.example/migration/legacy/account-oidc/callback","A".repeat(43),"plain");f.stored=f.data.serializeCode();
            assertThrows(WebApplicationException.class,()->f.redirect());assertThrows(ClientPolicyException.class,()->f.policy().executeOnEvent(f.tokenContext()));assertTrue(f.operations.isEmpty());assertTrue(f.access.getOtherClaims().isEmpty());
        }
    }
    interface Call{Object apply(String method,Object[] args);}
    @SuppressWarnings("unchecked")static <T>T proxy(Class<T> type,Call call){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,args)->call.apply(m.getName(),args));}
    static Object unexpected(String method){throw new AssertionError("Unexpected synthetic model call: "+method);}
    static final class Fixture implements AutoCloseable{
        final Instant now=Instant.parse("2026-10-18T00:00:00Z"),expiry=now.plusSeconds(60);
        final String codeId="00000000-0000-0000-0000-000000000001",event="00000000-0000-0000-0000-000000000002";
        final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
        String selectedClient="armoney-android";
        final List<CodeIntentHttp.Operation> operations=new ArrayList<>();final List<Map<String,Object>> requests=new ArrayList<>();
        final Map<String,String> notes=new HashMap<>(Map.of(PasswordProofFactory.NOTE,event,PasswordProofFactory.TIME_NOTE,now.minusSeconds(3).toString()));
        OAuth2Code data=data("com.armoney.android:/oauth/callback");Map<String,String> stored=data.serializeCode();boolean failStage,failConfirmation;
        final AccessToken access=new AccessToken();final RefreshToken refresh=new RefreshToken(access,null,"openid");final IDToken id=new IDToken();
        final RealmModel realm=proxy(RealmModel.class,(m,a)->switch(m){case "getId"->"realm";case "getName"->"armoney";case "getAccessTokenLifespan"->900;case "getAccessCodeLifespan"->60;default->unexpected(m);});
        final ClientModel client=proxy(ClientModel.class,(m,a)->switch(m){case "getId"->"internal";case "getClientId"->selectedClient;case "getAttribute"->null;case "isPublicClient","isStandardFlowEnabled"->true;case "isImplicitFlowEnabled","isDirectAccessGrantsEnabled"->false;default->unexpected(m);});
        final UserModel person=proxy(UserModel.class,(m,a)->m.equals("getId")?"subject":unexpected(m));
        final UserSessionModel user=proxy(UserSessionModel.class,(m,a)->switch(m){case "getId"->"sid";case "getRealm"->realm;case "getUser"->person;default->unexpected(m);});
        final AuthenticatedClientSessionModel clientSession=proxy(AuthenticatedClientSessionModel.class,(m,a)->switch(m){case "getClient"->client;case "getUserSession"->user;default->unexpected(m);});
        final ClientSessionContext clientContext=proxy(ClientSessionContext.class,(m,a)->m.equals("getClientSession")?clientSession:unexpected(m));
        final AuthenticationSessionModel auth=proxy(AuthenticationSessionModel.class,(m,a)->switch(m){case "getClient"->client;case "getClientNote"->a[0].equals("response_type")?"code":a[0].equals("redirect_uri")?"com.armoney.android:/oauth/callback":null;case "getAuthNote"->notes.get(a[0]);case "setAuthNote"->{notes.put((String)a[0],(String)a[1]);yield null;}default->unexpected(m);});
        final UriInfo uriDelegate=proxy(UriInfo.class,(m,a)->m.equals("getBaseUri")||m.equals("getRequestUri")?URI.create("https://issuer.example/sso/"):unexpected(m));
        final org.keycloak.urls.HostnameProvider hostname=proxy(org.keycloak.urls.HostnameProvider.class,(m,a)->m.equals("getBaseUri")?URI.create("https://issuer.example/sso/"):unexpected(m));
        final KeycloakUriInfo uri=new KeycloakUriInfo(proxy(KeycloakSession.class,(m,a)->m.equals("getProvider")?hostname:unexpected(m)),org.keycloak.urls.UrlType.FRONTEND,uriDelegate);
        final KeycloakContext context=proxy(KeycloakContext.class,(m,a)->switch(m){case "getRealm"->realm;case "getClient"->client;case "getUri"->uri;default->unexpected(m);});
        final CodeIntentLoginProtocolFactory owner;
        final KeycloakSession session;
        Fixture(){this(false);}
        Fixture(boolean account){
            if(account){selectedClient="armoney-migration-account";data=data("https://bank.example/migration/legacy/account-oidc/callback");stored=data.serializeCode();}
            access.iat(now.getEpochSecond());access.exp(now.plusSeconds(900).getEpochSecond());
            var env=account?CodeIntentProviderConfigurationTest.accountEnvironment():CodeIntentProviderConfigurationTest.valid();var config=CodeIntentProviderConfiguration.from(env);
            var resources=CodeIntentResources.open(env,config.keyMaterial(),CodeIntentHttp::new,(http,policy)->new CodeIntentClient((operation,fields,key,deadline)->{
                operations.add(operation);requests.add(new LinkedHashMap<>(fields));
                if(operation==CodeIntentHttp.Operation.STAGE){if(failStage)throw new CodeIntentHttp.Failure();return JsonSerialization.mapper.valueToTree(Map.of("intent_id",codeId,"code_key_id","current","state","STAGED","issued_at",fields.get("issued_at"),"expires_at",fields.get("expires_at")));}
                if(operation==CodeIntentHttp.Operation.KEY)return JsonSerialization.mapper.valueToTree(Map.of("code_key_id","current"));
                if(failConfirmation)throw new CodeIntentHttp.Failure();
                return JsonSerialization.mapper.valueToTree(Map.of("proof_id",codeId,"event_id",event,"kind","interactive","login_notice_eligible",true,"issued_at",now.toString(),"expires_at",expiry.toString()));
            },env.get("SSO_LOGIN_PROOF_WRITE_KEY"),env.get("SSO_CODE_INTENT_LOOKUP_KEY"),env.get("SSO_LOGIN_PROOF_CONFIRM_KEY"),clock,policy)).orElseThrow();
            owner=new CodeIntentLoginProtocolFactory(config,resources);
            var factory=proxy(KeycloakSessionFactory.class,(m,a)->m.equals("getProviderFactory")?owner:unexpected(m));
            var codes=proxy(SingleUseObjectProvider.class,(m,a)->m.equals("get")?stored:unexpected(m));
            session=proxy(KeycloakSession.class,(m,a)->switch(m){case "getKeycloakSessionFactory"->factory;case "getContext"->context;case "singleUseObjects"->codes;default->unexpected(m);});
        }
        OAuth2Code data(String redirect){return data(redirect,"A".repeat(43),"S256");}
        OAuth2Code data(String redirect,String challenge,String method){return new OAuth2Code(codeId,"internal",(int)expiry.getEpochSecond(),"nonce","openid",null,redirect,challenge,method,null,"sid");}
        Response redirect(){
            var protocol=new CodeIntentLoginProtocol(null,owner,clock,(r,a)->"original-auth-session");protocol.setSession(session).setRealm(realm).setUriInfo(uri);
            return protocol.buildRedirectUri(new OIDCRedirectUriBuilder(null){public OIDCRedirectUriBuilder addParam(String k,String v){throw new AssertionError();}public Response build(){return Response.status(302).location(URI.create(data.getRedirectUriParam()+"?code="+codeId+".sid.internal")).build();}},auth,user,clientContext);
        }
        CodeIntentTokenPolicy policy(){return new CodeIntentTokenPolicy(session,clock);}
        TokenResponseContext tokenContext()throws Exception{
            // Synthetic parser fixture supplies the result *after* the pinned grant's PKCE boundary.
            var constructor=OAuth2CodeParser.ParseResult.class.getDeclaredConstructor(String.class);constructor.setAccessible(true);var parsed=constructor.newInstance(codeId+".sid.internal");
            var field=OAuth2CodeParser.ParseResult.class.getDeclaredField("codeData");field.setAccessible(true);field.set(parsed,data);
            var parsedSession=OAuth2CodeParser.ParseResult.class.getDeclaredField("clientSession");parsedSession.setAccessible(true);parsedSession.set(parsed,clientSession);
            var builder=new TokenManager.AccessTokenResponseBuilder(realm,client,null,session,user,clientContext,new TokenManager()){
                @Override public AccessToken getAccessToken(){return access;}@Override public RefreshToken getRefreshToken(){return refresh;}@Override public IDToken getIdToken(){return id;}
            };
            return new TokenResponseContext(null,parsed,clientContext,builder);
        }
        @Override public void close(){owner.close();}
    }
}
