package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.services.clientpolicy.*;
import org.keycloak.services.clientpolicy.context.TokenResponseContext;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic boundary checks do not establish deployed provider/cluster acceptance. */
class CodeIntentProviderBoundaryTest {
    @Test void onlyQueryCodeWithExactSingleGeneratedCodeBindingIsAccepted(){
        CodeIntentLoginProtocol.requireCode("code",null);CodeIntentLoginProtocol.requireCode("code","query");
        for(String type:List.of("token","id_token","code token","code id_token",""))assertThrows(WebApplicationException.class,()->CodeIntentLoginProtocol.requireCode(type,null));
        for(String mode:List.of("form_post","fragment","QUERY",""))assertThrows(WebApplicationException.class,()->CodeIntentLoginProtocol.requireCode("code",mode));
        String code="00000000-0000-0000-0000-000000000001.sid.client";
        try(var response=Response.seeOther(URI.create("https://bank.example/callback?code="+code)).status(302).build()){
            assertEquals(code.substring(0,36),CodeIntentLoginProtocol.codeId(response,"sid","client"));
            assertThrows(WebApplicationException.class,()->CodeIntentLoginProtocol.codeId(response,"other","client"));
        }
        for(String suffix:List.of("?code="+code+"&code="+code,"?code="+code+"#fragment","?state=only","?code=wrong.sid.client"))
            try(var response=Response.status(302).location(URI.create("https://bank.example/callback"+suffix)).build()){
                assertThrows(RuntimeException.class,()->CodeIntentLoginProtocol.codeId(response,"sid","client"));
            }
    }
    @Test void publicStandardCodeS256ChecksRejectEveryAlternativeGrant(){
        CodeIntentLoginProtocol.requirePublicCode(client(""),"A".repeat(43),"S256");
        for(String field:List.of("isPublicClient","isStandardFlowEnabled","isImplicitFlowEnabled","isDirectAccessGrantsEnabled"))
            assertThrows(WebApplicationException.class,()->CodeIntentLoginProtocol.requirePublicCode(client(field),"A".repeat(43),"S256"));
        for(String method:List.of("plain","s256",""))assertThrows(WebApplicationException.class,()->CodeIntentLoginProtocol.requirePublicCode(client(""),"A".repeat(43),method));
        for(String challenge:List.of("","A".repeat(42),"A".repeat(44),"+".repeat(43)))assertThrows(WebApplicationException.class,()->CodeIntentLoginProtocol.requirePublicCode(client(""),challenge,"S256"));
    }
    @Test void refusedProviderResponseSurvivesActualPinnedSecurityHeaderFilter(){
        var failure=assertThrows(WebApplicationException.class,()->CodeIntentLoginProtocol.requireCode("code","form_post"));
        try(var response=failure.getResponse()){
            assertEquals(503,response.getStatus());assertNull(response.getEntity());assertEquals("no-store",response.getHeaderString("Cache-Control"));
            assertEquals(jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE,response.getMediaType());assertDoesNotThrow(()->StatusBoundaryTest.applyPinnedSecurityHeaders(response));
        }
    }
    @Test void nonCodePolicyEventsDoNotReadSessionOrMintClaims()throws Exception{
        var policy=new CodeIntentTokenPolicy(null,Clock.systemUTC());
        for(var event:List.of(ClientPolicyEvent.TOKEN_REFRESH_RESPONSE,ClientPolicyEvent.TOKEN_EXCHANGE_REQUEST,ClientPolicyEvent.TOKEN_INTROSPECT,ClientPolicyEvent.SERVICE_ACCOUNT_TOKEN_RESPONSE,ClientPolicyEvent.IMPLICIT_HYBRID_TOKEN_RESPONSE))policy.executeOnEvent(()->event);
        assertThrows(ClientPolicyException.class,()->policy.executeOnEvent(new TokenResponseContext(null,null,null,null)));
    }
    @Test void productionVerificationConstructorRejectsMissingCreationMarkerBeforeRenderingOrEnrollment(){
        var failures=new AtomicInteger();
        var context=(RequiredActionContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RequiredActionContext.class},(proxy,method,args)->{
            if(method.getName().equals("getUriInfo"))return null;
            if(method.getName().equals("failure")){failures.incrementAndGet();return null;}
            throw new AssertionError("Unexpected registration operation: "+method.getName());
        });
        var action=new VerificationAction("REGISTRATION_EMAIL","email",()->true);
        action.evaluateTriggers(context);action.requiredActionChallenge(context);action.processAction(context);
        assertEquals(2,failures.get());
    }
    private static ClientModel client(String flip){return (ClientModel)Proxy.newProxyInstance(CodeIntentProviderBoundaryTest.class.getClassLoader(),new Class<?>[]{ClientModel.class},(proxy,method,args)->{
        boolean ordinary=switch(method.getName()){case "isPublicClient","isStandardFlowEnabled"->true;case "isImplicitFlowEnabled","isDirectAccessGrantsEnabled"->false;default->throw new AssertionError(method.getName());};return method.getName().equals(flip)?!ordinary:ordinary;
    });}
}
