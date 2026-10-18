package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.common.util.Time;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.*;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.sessions.*;
import jakarta.ws.rs.core.*;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic live auth-session models; actual restart endpoint still needs isolated runtime acceptance. */
class CodeIntentRegistrationContinuationTest {
    @Test void projectedPhoneChallengesBeforeSuccessAndPreservesOriginalOidcRequest()throws Exception{
        var f=new Fixture();var before=new HashMap<>(f.clientNotes);
        var action=new VerificationAction("REGISTRATION_PHONE","phone",()->true,ignored->true,c->CodeIntentRegistrationContinuation.show(c,Fixture.KEY,ignored->true));
        assertTrue(action.complete(f.context(),new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"email_verified\":true,\"phone_verified\":true,\"state\":\"PROJECTED\"}")));
        assertEquals(1,f.challenges);assertEquals(0,f.success);assertEquals(0,f.codeRequests);
        assertEquals("Registration verified. Sign in to continue.",f.message);
        URI restart=URI.create((String)f.attributes.get("actionUri"));assertEquals("/sso/realms/armoney/login-actions/restart",restart.getPath());
        assertTrue(restart.getRawQuery().contains("client_id=armoney-android"));assertTrue(restart.getRawQuery().contains("tab_id=tab"));assertTrue(restart.getRawQuery().contains("skip_logout=true"));
        before.remove(org.keycloak.protocol.AuthorizationEndpointBase.APP_INITIATED_FLOW);assertEquals(before,f.clientNotes);
        try(var response=f.response){assertEquals(200,response.getStatus());assertNotNull(response.getHeaderString("Cache-Control"));assertTrue(CacheControl.valueOf(response.getHeaderString("Cache-Control")).isNoStore());assertDoesNotThrow(()->StatusBoundaryTest.applyPinnedSecurityHeaders(response));}
    }
    @Test void persistentUserActionsMissingOriginalTabExpiredFlowOrMissingCreationMarkerCannotContinue(){
        for(String fault:List.of("persistent-email","persistent-phone","missing-tab","expired","missing-marker","disabled")){
            var f=new Fixture();f.fault=fault;
            assertThrows(Bridge.Unavailable.class,()->CodeIntentRegistrationContinuation.show(f.context(),Fixture.KEY,ignored->true));
            assertEquals(0,f.challenges);assertEquals(0,f.success);assertEquals(0,f.codeRequests);assertTrue(f.attributes.isEmpty());
        }
    }
    @Test void absentCreationGuardOrUnprojectedReceiptsCannotRenderTerminalPage()throws Exception{
        var f=new Fixture();var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var denied=new VerificationAction("REGISTRATION_PHONE","phone",()->true,ignored->false,c->{throw new AssertionError();});
        assertThrows(Bridge.Unavailable.class,()->denied.complete(f.context(),json.readTree("{\"email_verified\":true,\"phone_verified\":true,\"state\":\"PROJECTED\"}")));
        var action=new VerificationAction("REGISTRATION_PHONE","phone",()->true,ignored->true,c->{throw new AssertionError();});
        for(String state:List.of("PENDING_CONTACTS","PROJECTION_PENDING","SUSPENDED","BLOCKED_MIGRATION","ACTIVE"))assertFalse(action.complete(f.context(),json.readTree("{\"email_verified\":true,\"phone_verified\":true,\"state\":\""+state+"\"}")));
        assertEquals(0,f.success);assertEquals(0,f.challenges);
    }
    interface Call{Object apply(String name,Object[] args);}
    @SuppressWarnings("unchecked")static <T>T model(Class<T> type,Call call){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.apply(m.getName(),a));}
    static Object unexpected(String name){throw new AssertionError("Unexpected registration fixture operation: "+name);}
    static final class Fixture{
        static final String KEY="isolated-synthetic-registration-binding-key";
        final Map<String,String> clientNotes=new HashMap<>(Map.of("response_type","code","state","original-state","nonce","original-nonce","code_challenge","A".repeat(43),"code_challenge_method","S256",org.keycloak.protocol.AuthorizationEndpointBase.APP_INITIATED_FLOW,"registration"));
        final Map<String,Object> attributes=new HashMap<>();String fault="",message;int challenges,success,codeRequests;Response response;
        RealmModel realm(){return model(RealmModel.class,(n,a)->switch(n){case "getName"->"armoney";case "getId"->"realm";case "getClientByClientId"->client();case "getAccessCodeLifespanUserAction"->120;case "getAccessCodeLifespanLogin"->600;case "getAccessCodeLifespan"->60;case "getBrowserSecurityHeaders"->Map.of();default->unexpected(n);});}
        ClientModel client(){return model(ClientModel.class,(n,a)->switch(n){case "getId"->"internal";case "getClientId"->"armoney-android";case "isEnabled","isStandardFlowEnabled"->true;default->unexpected(n);});}
        UserModel user(){return model(UserModel.class,(n,a)->switch(n){case "getId"->"subject";case "getUsername"->"+971501234567";case "getEmail"->"synthetic@example.test";case "isEnabled"->!fault.equals("disabled");case "getRequiredActionsStream"->fault.equals("persistent-email")?Stream.of("armoney-verify-email-code"):fault.equals("persistent-phone")?Stream.of("armoney-verify-phone-code"):Stream.empty();default->unexpected(n);});}
        RootAuthenticationSessionModel root(){return model(RootAuthenticationSessionModel.class,(n,a)->switch(n){case "getId"->"root";case "getTimestamp"->Time.currentTime()-(fault.equals("expired")?1000:0);case "getAuthenticationSession"->fault.equals("missing-tab")?null:auth();default->unexpected(n);});}
        AuthenticationSessionModel auth(){return model(AuthenticationSessionModel.class,(n,a)->switch(n){case "getClient"->client();case "getParentSession"->root();case "getTabId"->"tab";case "getAction"->"REQUIRED_ACTIONS";case "getAuthenticatedUser"->user();case "getClientNote"->clientNotes.get(a[0]);case "getClientNotes"->clientNotes;case "getProtocol"->"openid-connect";case "getRedirectUri"->"com.armoney.android:/oauth/callback";case "removeClientNote"->{clientNotes.remove(a[0]);yield null;}case "getAuthNote"->fault.equals("missing-marker")?null:CodeIntentRegistrationGuard.value(KEY,"realm",auth(),"subject");default->unexpected(n);});}
        KeycloakUriInfo uri(){var hostname=model(org.keycloak.urls.HostnameProvider.class,(n,a)->n.equals("getBaseUri")?URI.create("https://issuer.example/sso/"):unexpected(n));return new KeycloakUriInfo(model(KeycloakSession.class,(n,a)->n.equals("getProvider")?hostname:unexpected(n)),org.keycloak.urls.UrlType.FRONTEND,null);}
        LoginFormsProvider form(){return model(LoginFormsProvider.class,(n,a)->switch(n){case "setAuthenticationSession"->form();case "setInfo"->{message=(String)a[0];yield form();}case "setAttribute"->{attributes.put((String)a[0],a[1]);yield form();}case "createInfoPage"->Response.ok("synthetic fixed info",MediaType.TEXT_HTML_TYPE).build();default->unexpected(n);});}
        KeycloakSession session(){return model(KeycloakSession.class,(n,a)->switch(n){case "getContext"->model(KeycloakContext.class,(name,args)->switch(name){case "getRealm"->realm();case "getUri"->uri();default->unexpected(name);});case "authenticationSessions"->model(AuthenticationSessionProvider.class,(name,args)->name.equals("getRootAuthenticationSession")?root():unexpected(name));case "getProvider"->{if(a[0]==LoginFormsProvider.class)yield form();if(a[0]==org.keycloak.protocol.LoginProtocol.class)yield new OIDCLoginProtocol(null);codeRequests++;yield unexpected(n);}default->unexpected(n);});}
        RequiredActionContext context(){return model(RequiredActionContext.class,(n,a)->switch(n){case "getUser"->user();case "getRealm"->realm();case "getAuthenticationSession"->auth();case "getSession"->session();case "getUriInfo"->uri();case "challenge"->{challenges++;response=(Response)a[0];yield null;}case "success"->{success++;yield null;}default->unexpected(n);});}
    }
}
