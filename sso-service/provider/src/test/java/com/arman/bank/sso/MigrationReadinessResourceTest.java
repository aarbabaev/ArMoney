package com.arman.bank.sso;

import org.junit.jupiter.api.*;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.parallel.ResourceLock("keycloak-profile")
final class MigrationReadinessResourceTest {
    private static final String KEY="synthetic-status-credential-"+"x".repeat(32);
    private static boolean ownsProfile;
    @BeforeAll static void profile(){ownsProfile=org.keycloak.common.Profile.getInstance()==null;if(ownsProfile)org.keycloak.common.Profile.defaults();}
    @AfterAll static void release(){if(ownsProfile)org.keycloak.common.Profile.reset();}
    private static StatusResource resource(boolean enabled,java.util.function.Supplier<CodeIntentProviderConfiguration> config){
        return new StatusResource(MigrationReadinessObservationTest.fixture().session(),()->KEY,()->"b".repeat(40),()->false,()->enabled,config);
    }
    private static byte[] request()throws Exception{return org.keycloak.util.JsonSerialization.writeValueAsBytes(MigrationReadinessObservationTest.request());}
    private static void response(Response response,int status){try(response){assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeaderString("Cache-Control"));assertEquals("application/json",response.getMediaType().toString());}}
    @Test void authenticationPrecedesParsingAndConfigurationAndBodyIsBounded()throws Exception{
        var calls=new AtomicInteger();var resource=resource(true,()->{calls.incrementAndGet();throw new IllegalStateException("synthetic outage");});
        response(resource.migrationReadiness("incorrect",new byte[12289]),401);assertEquals(0,calls.get());
        response(resource.migrationReadiness(null,null),401);assertEquals(0,calls.get());
        response(resource.migrationReadiness(KEY,new byte[12289]),400);response(resource.migrationReadiness(KEY,null),400);
        response(resource.migrationReadiness(KEY,"{}".getBytes(StandardCharsets.UTF_8)),400);assertEquals(0,calls.get());
        response(resource.migrationReadiness(KEY,request()),503);assertEquals(1,calls.get());
        response(resource(false,MigrationReadinessObservationTest::configuration).migrationReadiness(KEY,request()),503);
    }
    @Test void exactV2ResponseAndV1OutputRemainSeparate()throws Exception{
        var resource=resource(true,MigrationReadinessObservationTest::configuration);
        try(var result=resource.migrationReadiness(KEY,request())){
            assertEquals(200,result.getStatus());assertEquals("no-store",result.getHeaderString("Cache-Control"));
            var body=(com.fasterxml.jackson.databind.JsonNode)result.getEntity();assertEquals(5,body.size());assertEquals(12,body.path("facts").size());assertTrue(body.path("provider_email_verified").isBoolean());
        }
        var tuple=MigrationReadinessObservationTest.request();tuple.remove("protocol_version");tuple.remove("purpose");tuple.remove("original_redirect_uri");
        try(var original=resource.codeSession(KEY,org.keycloak.util.JsonSerialization.writeValueAsBytes(tuple))){assertEquals(200,original.getStatus());assertEquals(12,((com.fasterxml.jackson.databind.JsonNode)original.getEntity()).size());}
    }
    @Test void duplicateFieldsAndUnknownSchemaFailBeforeObservation()throws Exception{
        var calls=new AtomicInteger();var resource=resource(true,()->{calls.incrementAndGet();return MigrationReadinessObservationTest.configuration();});
        String valid=new String(request(),StandardCharsets.UTF_8);
        response(resource.migrationReadiness(KEY,valid.replace("{","{\"protocol_version\":2,").getBytes(StandardCharsets.UTF_8)),400);
        var node=MigrationReadinessObservationTest.request();node.put("provider_email_verified",true);
        response(resource.migrationReadiness(KEY,org.keycloak.util.JsonSerialization.writeValueAsBytes(node)),400);assertEquals(0,calls.get());
    }
}
