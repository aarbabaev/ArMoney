package com.arman.bank.sso;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.keycloak.models.*;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.parallel.ResourceLock("keycloak-profile")
final class MigrationReadinessObservationTest {
    static final String ISSUER="https://issuer.example/sso/realms/armoney";
    static final String REDIRECT="https://bank.example/migration/managed/callback";
    private static boolean ownsProfile;
    @BeforeAll static void profile(){ownsProfile=org.keycloak.common.Profile.getInstance()==null;if(ownsProfile)org.keycloak.common.Profile.defaults();}
    @AfterAll static void release(){if(ownsProfile)org.keycloak.common.Profile.reset();}
    static CodeIntentProviderConfiguration configuration(){return CodeIntentProviderConfiguration.from(CodeIntentProviderConfigurationTest.valid());}
    static CodeSessionObservationTest.Fixture fixture(){
        var f=new CodeSessionObservationTest.Fixture();f.clientId="armoney-android";f.base=java.net.URI.create("https://issuer.example/sso/");
        f.binding=new CodeSessionObservation.Binding(ISSUER,"subject",f.clientId,"session");
        f.clientOverrides.putAll(Map.of("isStandardFlowEnabled",true,"isImplicitFlowEnabled",false,"isDirectAccessGrantsEnabled",false,
                "isServiceAccountsEnabled",false,"getRedirectUris",Set.of(REDIRECT),"getAttribute",(CodeSessionObservationTest.Call)args->"pkce.code.challenge.method".equals(args[0])?"S256":null));
        f.cs.put("getRedirectUri",REDIRECT);f.user.put("getEmail","person@example.test");f.user.put("getUsername","+971501234567");f.user.put("isEmailVerified",true);return f;
    }
    static ObjectNode request(){
        var node=org.keycloak.util.JsonSerialization.mapper.createObjectNode();node.put("protocol_version",2);node.put("purpose","MIGRATION_MANAGED");
        node.put("original_redirect_uri",REDIRECT);node.put("issuer",ISSUER);node.put("subject","subject");node.put("client_id","armoney-android");node.put("user_session_id","session");return node;
    }
    static Set<String> keys(com.fasterxml.jackson.databind.JsonNode node){var fields=new HashSet<String>();node.fieldNames().forEachRemaining(fields::add);return fields;}
    @Test void versionedEnvelopePreservesV1AndCarriesRawBooleanFromSameResolvedUser(){
        var f=fixture();var session=f.session();var parsed=MigrationReadinessObservation.parse(request());
        var result=MigrationReadinessObservation.observe(session,parsed,Clock.systemUTC(),configuration());
        assertEquals(Set.of("protocol_version","purpose","original_redirect_uri","facts","provider_email_verified"),keys(result));
        assertEquals(2,result.path("protocol_version").intValue());assertEquals("MIGRATION_MANAGED",result.path("purpose").textValue());
        assertEquals(REDIRECT,result.path("original_redirect_uri").textValue());assertTrue(result.path("provider_email_verified").isBoolean());assertTrue(result.path("provider_email_verified").booleanValue());
        assertEquals(Set.of("issuer","subject","client_id","user_session_id","username","email","phone_number","enabled","user_session_active","client_session_active","observed_at","valid_until"),keys(result.path("facts")));
        assertEquals(12,CodeSessionObservation.observe(session,f.binding,Clock.systemUTC(),configuration()).size());
        f.user.put("isEmailVerified",false);result=MigrationReadinessObservation.observe(session,parsed,Clock.systemUTC(),configuration());
        assertTrue(result.path("provider_email_verified").isBoolean());assertFalse(result.path("provider_email_verified").booleanValue());
        f.user.put("isEnabled",false);assertFalse(MigrationReadinessObservation.observe(session,parsed,Clock.systemUTC(),configuration()).path("facts").path("enabled").booleanValue());
    }
    @Test void exactSevenFieldSchemaRejectsAliasesWrongTypesAndUnknownPurpose(){
        for(String field:keys(request())){var node=request();node.remove(field);assertThrows(IllegalArgumentException.class,()->MigrationReadinessObservation.parse(node),field);}
        for(String fault:List.of("extra","version","versionString","versionHuge","purpose","purposeType","redirectNull","subjectType")){
            var node=request();switch(fault){
                case "extra"->node.put("email_verified",true);case "version"->node.put("protocol_version",1);case "versionString"->node.put("protocol_version","2");
                case "versionHuge"->node.put("protocol_version",4294967298L);case "purpose"->node.put("purpose","BANK_NATIVE");case "purposeType"->node.put("purpose",true);
                case "redirectNull"->node.putNull("original_redirect_uri");case "subjectType"->node.put("subject",7);
            }assertThrows(IllegalArgumentException.class,()->MigrationReadinessObservation.parse(node),fault);
        }
    }
    @Test void wrongTuplePurposeAndActualRedirectNeverReturnReadiness(){
        for(String fault:List.of("issuer","subject","client_id","user_session_id","purpose","original_redirect_uri","actualRedirect","offline","expired","confidential","pkce","disabledClient")){
            var f=fixture();var node=request();switch(fault){
                case "issuer"->node.put("issuer",ISSUER+"/");case "subject"->f.user.put("getId","foreign");case "client_id"->node.put("client_id","armoney-ios");
                case "user_session_id"->f.us.put("getId","foreign");case "purpose"->node.put("purpose","MIGRATION_ACCOUNT");case "original_redirect_uri"->node.put("original_redirect_uri",REDIRECT+"/");
                case "actualRedirect"->f.cs.put("getRedirectUri","com.armoney.android:/oauth/callback");case "offline"->f.us.put("isOffline",true);case "expired"->f.us.put("getLastSessionRefresh",1);
                case "confidential"->f.publicClient=false;case "pkce"->f.clientOverrides.put("getAttribute",(CodeSessionObservationTest.Call)args->"plain");case "disabledClient"->f.clientOverrides.put("isEnabled",false);
            }
            assertThrows(RuntimeException.class,()->MigrationReadinessObservation.observe(f.session(),MigrationReadinessObservation.parse(node),Clock.systemUTC(),configuration()),fault);
        }
    }
    @Test void exactAccountPurposeAndRestrictedClientAreSupported(){
        var f=fixture();String redirect="https://bank.example/migration/legacy/account-oidc/callback";f.clientId="armoney-migration-account";
        f.binding=new CodeSessionObservation.Binding(ISSUER,"subject",f.clientId,"session");f.clientOverrides.put("getRedirectUris",Set.of(redirect));f.cs.put("getRedirectUri",redirect);
        var node=request();node.put("client_id",f.clientId);node.put("purpose","MIGRATION_ACCOUNT");node.put("original_redirect_uri",redirect);
        var config=CodeIntentProviderConfiguration.from(CodeIntentProviderConfigurationTest.accountEnvironment());
        assertTrue(MigrationReadinessObservation.observe(f.session(),MigrationReadinessObservation.parse(node),Clock.systemUTC(),config).path("provider_email_verified").booleanValue());
    }
    @Test void observedContactMutationAndUnavailableConfigurationFailClosed(){
        var f=fixture();f.user.put("isEmailVerified",(CodeSessionObservationTest.Call)args->{f.user.put("getEmail","changed@example.test");return true;});
        assertThrows(Bridge.Unavailable.class,()->MigrationReadinessObservation.observe(f.session(),MigrationReadinessObservation.parse(request()),Clock.systemUTC(),configuration()));
        var good=fixture();assertThrows(Bridge.Unavailable.class,()->MigrationReadinessObservation.observe(good.session(),MigrationReadinessObservation.parse(request()),Clock.systemUTC(),CodeIntentProviderConfiguration.from(Map.of())));
    }
}
