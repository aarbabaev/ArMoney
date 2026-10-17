package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import jakarta.ws.rs.core.HttpHeaders;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("code-intent-fixture-install")
class CodeIntentFixtureResourceTest {
    @Test void onlyStrictUtf8WithoutBomIsAccepted()throws Exception{
        assertEquals("café",CodeIntentFixtureResource.strictUtf8("{\"x\":\"café\"}".getBytes(StandardCharsets.UTF_8)).get("x").textValue());
        for(byte[] bytes:List.of("{}".getBytes(StandardCharsets.UTF_16),"{}".getBytes(StandardCharsets.UTF_16LE),"{}".getBytes(StandardCharsets.UTF_16BE),new byte[]{0,0,0,123,0,0,0,125},new byte[]{(byte)239,(byte)187,(byte)191,123,125},new byte[]{(byte)195,40}))
            assertThrows(IOException.class,()->CodeIntentFixtureResource.strictUtf8(bytes));
    }
    static HttpHeaders headers(String value,boolean duplicate){return (HttpHeaders)Proxy.newProxyInstance(HttpHeaders.class.getClassLoader(),new Class<?>[]{HttpHeaders.class},(p,m,a)->{if(m.getName().equals("getRequestHeader"))return duplicate?List.of(value,value):List.of(value);throw new AssertionError(m.getName());});}
    @Test void wrongOrDuplicateCredentialNeverReadsBody(){try(var h=new CodeIntentFixtureSupportTest.Harness()){
        CodeIntentFixtureSupport.install(h.support);var resource=new CodeIntentFixtureResource(null,CodeIntentFixtureBodyDeadline.create(new java.util.concurrent.Semaphore(1),System::nanoTime,()->{},(millis,task)->()->{}));
        InputStream forbidden=new InputStream(){public int read(){throw new AssertionError("Unauthenticated body read");}};
        try(var response=resource.control("arm",headers("wrong",false),forbidden)){assertEquals(401,response.getStatus());assertNull(response.getEntity());assertEquals("no-store",response.getHeaderString("Cache-Control"));}
        try(var response=resource.control("arm",headers(CodeIntentFixtureSupportTest.CONTROL,true),forbidden)){assertEquals(401,response.getStatus());}
    }}
    @Test void strictJsonBodyCapAndControlSchemaRejectWithoutRemoteWork(){try(var h=new CodeIntentFixtureSupportTest.Harness()){
        CodeIntentFixtureSupport.install(h.support);var resource=new CodeIntentFixtureResource(null,CodeIntentFixtureBodyDeadline.create(new java.util.concurrent.Semaphore(1),System::nanoTime,()->{},(millis,task)->()->{}));
        for(String body:List.of("{\"subject\":\"a\",\"subject\":\"b\"}","{\"subject\":null,\"client_id\":\"armoney-ios\"}","{\"subject\":\"a\",\"client_id\":\"armoney-ios\",\"verified\":true}","x".repeat(12289))){
            try(var response=resource.control("arm",headers(CodeIntentFixtureSupportTest.CONTROL,false),new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))){assertEquals(400,response.getStatus());assertNull(response.getEntity());}
        }
        assertEquals(0,h.peer.calls);assertEquals(0,h.production.calls);
    }}
    @Test void membershipDtoRejectsSyntheticFlagsForeignBindingAndUnknownFields()throws Exception{
        var binding=new LoginProof.Binding(CodeIntentFixtureSupportTest.ISSUER,"subject","armoney-ios","native");
        String good="{\"issuer\":\""+binding.issuer()+"\",\"subject\":\"subject\",\"client_id\":\"armoney-ios\",\"user_session_id\":\"native\",\"user_session_present\":true,\"client_session_present\":true}";
        CodeIntentFixtureSupport.validateMembership(Bridge.strictJson(good.getBytes(StandardCharsets.UTF_8)),binding);
        for(String bad:List.of(good.replace("true","\"true\""),good.replace("\"native\"","\"foreign\""),good.replace("}",",\"verified\":true}"),good.replace("true","false")))
            assertThrows(RuntimeException.class,()->CodeIntentFixtureSupport.validateMembership(Bridge.strictJson(bad.getBytes(StandardCharsets.UTF_8)),binding));
    }
}
