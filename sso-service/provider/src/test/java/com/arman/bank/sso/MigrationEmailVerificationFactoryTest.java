package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import org.keycloak.authentication.*;
import org.keycloak.models.*;
import org.keycloak.sessions.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class MigrationEmailVerificationFactoryTest {
    @Test void firstAuthenticatedLoginAndRequiredActionReentryTriggerForBothMigrationPurposes(){
        for(boolean account:new boolean[]{false,true})for(String phase:List.of("AUTHENTICATE","REQUIRED_ACTIONS")){
            var f=Fixture.migration(account);f.action=phase;
            var action=MigrationEmailVerificationFactory.action(MigrationReadinessObservationTest::configuration);
            action.evaluateTriggers(f.context());assertEquals(Set.of("VERIFY_EMAIL"),f.actions,phase);
            action.evaluateTriggers(f.context());assertEquals(Set.of("VERIFY_EMAIL"),f.actions,"repeated evaluation is idempotent");
            assertFalse((Boolean)f.user.get("isEmailVerified"));
            f.user.put("isEmailVerified",true);f.actions.clear();action.evaluateTriggers(f.context());assertTrue(f.actions.isEmpty(),"verified repeat login");
        }
    }
    @Test void strictRegistrationMarkerExcludesBothMigrationPurposesInBothLifecyclePhases(){
        for(boolean account:new boolean[]{false,true})for(String phase:List.of("AUTHENTICATE","REQUIRED_ACTIONS")){
            var f=Fixture.migration(account);f.action=phase;f.creation=true;
            MigrationEmailVerificationFactory.action(MigrationReadinessObservationTest::configuration).evaluateTriggers(f.context());
            assertTrue(f.actions.isEmpty(),phase);assertFalse((Boolean)f.user.get("isEmailVerified"));
        }
    }
    @Test void unrelatedOrMissingLifecyclePhasesNeverTrigger(){
        for(boolean account:new boolean[]{false,true})for(String phase:Arrays.asList(null,"LOGGED_OUT","OAUTH_GRANT","unknown")){
            var f=Fixture.migration(account);f.action=phase;
            MigrationEmailVerificationFactory.action(MigrationReadinessObservationTest::configuration).evaluateTriggers(f.context());assertTrue(f.actions.isEmpty());
        }
    }
    @Test void exactMigrationFlowEnqueuesOnlyStockActionWithoutMutatingVerification(){
        var f=new Fixture();var action=MigrationEmailVerificationFactory.action(MigrationReadinessObservationTest::configuration);
        action.evaluateTriggers(f.context());assertEquals(Set.of("VERIFY_EMAIL"),f.actions);assertFalse((Boolean)f.user.get("isEmailVerified"));
        f.user.put("isEmailVerified",true);f.actions.clear();action.evaluateTriggers(f.context());assertTrue(f.actions.isEmpty());
        action.close();var factory=new MigrationEmailVerificationFactory();assertEquals("armoney-migration-email-verification",factory.getId());factory.init(null);factory.postInit(null);factory.close();
    }
    @Test void strictCreationMarkerDisabledUserUnrelatedFlowAndUnverifiedContactDoNotTrigger(){
        for(String phase:List.of("AUTHENTICATE","REQUIRED_ACTIONS"))for(String fault:List.of("creation","disabled","verified","email","native","redirect","issuer","subject","action","off")){
            var f=new Fixture();f.action=phase;var config=MigrationReadinessObservationTest.configuration();switch(fault){
                case "creation"->f.creation=true;case "disabled"->f.user.put("isEnabled",false);case "verified"->f.user.put("isEmailVerified",true);
                case "email"->f.user.put("getEmail","Person@example.test");case "native"->f.redirect="com.armoney.android:/oauth/callback";
                case "redirect"->f.redirect=MigrationReadinessObservationTest.REDIRECT+"/";case "issuer"->f.base=java.net.URI.create("https://foreign.example/sso/");
                case "subject"->f.authSubject="other";case "action"->f.action="LOGGED_OUT";case "off"->config=CodeIntentProviderConfiguration.from(Map.of());
            }
            var selected=config;MigrationEmailVerificationFactory.action(()->selected).evaluateTriggers(f.context());assertTrue(f.actions.isEmpty(),fault);
        }
    }
    @Test void recognizedMigrationWithUnsafeClientFailsClosedAndCannotRunAsAnAction(){
        var f=new Fixture();f.clientOverrides.put("isPublicClient",false);var action=MigrationEmailVerificationFactory.action(MigrationReadinessObservationTest::configuration);
        assertThrows(Bridge.Unavailable.class,()->action.evaluateTriggers(f.context()));assertTrue(f.actions.isEmpty());
        action.requiredActionChallenge(f.context());action.processAction(f.context());assertEquals(2,f.failures);
    }
    static final class Fixture {
        final Map<String,Object> user=new HashMap<>(Map.of("getId","subject","isEnabled",true,"isEmailVerified",false,"getEmail","person@example.test"));
        final Map<String,Object> clientOverrides=new HashMap<>();final Set<String> actions=new HashSet<>();
        String redirect=MigrationReadinessObservationTest.REDIRECT,authSubject="subject",action="REQUIRED_ACTIONS";boolean creation;int failures;
        java.net.URI base=java.net.URI.create("https://issuer.example/sso/");
        static Fixture migration(boolean account){
            var fixture=new Fixture();
            if(account){
                fixture.redirect="https://bank.example/migration/legacy/account-oidc/callback";
                fixture.clientOverrides.put("getClientId","armoney-web");fixture.clientOverrides.put("getRedirectUris",Set.of(fixture.redirect));
            }
            return fixture;
        }
        RequiredActionContext context(){
            var clientValues=new HashMap<String,Object>(Map.of("getId","internal","getClientId","armoney-android","isEnabled",true,"isPublicClient",true,
                    "isStandardFlowEnabled",true,"isImplicitFlowEnabled",false,"isDirectAccessGrantsEnabled",false,"isServiceAccountsEnabled",false,
                    "getRedirectUris",Set.of(MigrationReadinessObservationTest.REDIRECT,"com.armoney.android:/oauth/callback"),"getAttribute",(CodeSessionObservationTest.Call)args->"S256"));clientValues.putAll(clientOverrides);
            var client=CodeSessionObservationTest.model(ClientModel.class,clientValues);
            var model=CodeSessionObservationTest.model(UserModel.class,user);
            var root=CodeSessionObservationTest.model(RootAuthenticationSessionModel.class,Map.of("getId","root"));
            var values=new HashMap<String,Object>();values.put("getClient",client);values.put("getParentSession",root);values.put("getTabId","tab");
            values.put("getAction",action);values.put("getRedirectUri",redirect);
            values.put("getAuthenticatedUser",CodeSessionObservationTest.model(UserModel.class,Map.of("getId",authSubject)));
            values.put("getAuthNote",(CodeSessionObservationTest.Call)args->null);values.put("addRequiredAction",(CodeSessionObservationTest.Call)args->{actions.add(args[0].toString());return null;});
            var auth=CodeSessionObservationTest.model(AuthenticationSessionModel.class,values);
            if(creation){String marker=CodeIntentRegistrationGuard.value("b".repeat(40),"realm",auth,"subject");values.put("getAuthNote",(CodeSessionObservationTest.Call)args->marker);}
            var realm=CodeSessionObservationTest.model(RealmModel.class,Map.of("getId","realm","getName","armoney"));
            var hostname=CodeSessionObservationTest.model(org.keycloak.urls.HostnameProvider.class,Map.of("getBaseUri",base));
            var host=CodeSessionObservationTest.model(KeycloakSession.class,Map.of("getProvider",hostname));
            var delegate=CodeSessionObservationTest.model(jakarta.ws.rs.core.UriInfo.class,Map.of("getBaseUri",base,"getRequestUri",base));
            var uri=new KeycloakUriInfo(host,org.keycloak.urls.UrlType.FRONTEND,delegate);
            return CodeSessionObservationTest.model(RequiredActionContext.class,Map.of("getAuthenticationSession",auth,"getUser",model,"getRealm",realm,"getUriInfo",uri,"failure",(CodeSessionObservationTest.Call)args->{failures++;return null;}));
        }
    }
}
