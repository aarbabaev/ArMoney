package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.models.*;
import org.keycloak.sessions.AuthenticationSessionModel;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("code-intent-fixture-install")
class CodeIntentFixtureActionTest {
    @Test void missingGenuinePasswordProofNeverCompletesOrStartsRemoteWork(){try(var h=new CodeIntentFixtureSupportTest.Harness()){
        CodeIntentFixtureSupport.install(h.support);h.support.arm("subject","armoney-ios");var failed=new AtomicInteger();
        var user=model(UserModel.class,Map.of("isEnabled",true));var client=model(ClientModel.class,Map.of("isPublicClient",true));
        var auth=model(AuthenticationSessionModel.class,new HashMap<>(){{put("getAuthenticatedUser",user);put("getAction","REQUIRED_ACTIONS");put("getClient",client);put("getAuthNote",null);}});
        var context=(RequiredActionContext)Proxy.newProxyInstance(RequiredActionContext.class.getClassLoader(),new Class<?>[]{RequiredActionContext.class},(p,m,a)->switch(m.getName()){
            case "getAuthenticationSession"->auth;case "getRealm"->model(RealmModel.class,Map.of());case "failure"->{failed.incrementAndGet();yield null;}default->throw new AssertionError("Unexpected native operation: "+m.getName());});
        new CodeIntentFixtureAction().requiredActionChallenge(context);assertEquals(1,failed.get());assertEquals(0,h.peer.calls);assertEquals(0,h.production.calls);
    }}
    @Test void templateUsesNativeActionWithoutHandlesOrAuthorityFields()throws Exception{
        try(var stream=getClass().getResourceAsStream("/theme/armoney-code-intent-fixture/login/code-intent-continuation.ftl")){
            assertNotNull(stream);String template=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(template.contains("${url.loginAction}"));assertTrue(template.contains("method=\"post\""));
            for(String forbidden:List.of("experiment_handle","stage_handle","event_id","password_authenticated_at","verified","receipt"))assertFalse(template.contains(forbidden));
        }
        for(var field:CodeIntentFixtureAction.class.getDeclaredFields())assertFalse(RequiredActionContext.class.isAssignableFrom(field.getType())||KeycloakSession.class.isAssignableFrom(field.getType()));
    }
    @SuppressWarnings("unchecked")private static <T>T model(Class<T> type,Map<String,Object> values){return(T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{if(!values.containsKey(m.getName()))throw new AssertionError(m.getName());return values.get(m.getName());});}
}
