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
    @Test void twoFieldBindRequestPinsProviderReadinessAndReturnsOnlyState()throws Exception{try(var h=new CodeIntentFixtureSupportTest.Harness()){
        CodeIntentFixtureSupport.install(h.support);String handle=h.ready();var e=h.support.entry(handle);var command=UUID.randomUUID();
        String stage=CodeIntentFixtureSupport.handle();h.peer.body="{\"stage_handle\":\""+stage+"\"}";
        String input="{\"experiment_handle\":\""+handle+"\",\"command_id\":\""+command+"\"}";
        try(var response=resource().control("bind-stage",headers(CodeIntentFixtureSupportTest.CONTROL,false),body(input))){
            assertEquals(200,response.getStatus());assertEquals(Map.of("state","STAGE_BOUND"),response.getEntity());
            assertEquals("no-store",response.getHeaderString("Cache-Control"));
        }
        assertEquals(stage,e.stageHandle);assertEquals(1,h.peer.calls);assertFalse(e.permissionClaimed);
        assertEquals("/fixture/code-intent-authority/readiness",h.peer.request.uri().getPath());
        var sent=Bridge.strictJson(publishedBody(h.peer.request));
        assertEquals(Set.of("experiment_handle","command_id"),fieldNames(sent));
        assertEquals(handle,sent.get("experiment_handle").textValue());assertEquals(e.prepare.toString(),sent.get("command_id").textValue());
        assertNotEquals(command.toString(),sent.get("command_id").textValue());assertEquals(0,h.production.calls);
        try(var response=resource().control("state",headers(CodeIntentFixtureSupportTest.CONTROL,false),body("{\"experiment_handle\":\""+handle+"\"}"))){
            assertEquals(200,response.getStatus());assertEquals(Map.of("state","STAGE_BOUND"),response.getEntity());
        }
        assertEquals(1,h.peer.calls);
    }}
    @Test void oldStageFieldAndNoncanonicalBindSchemaAreRejectedBeforeReadiness(){try(var h=new CodeIntentFixtureSupportTest.Harness()){
        CodeIntentFixtureSupport.install(h.support);String handle=h.ready();var command=UUID.randomUUID();
        String prefix="{\"experiment_handle\":\""+handle+"\",\"command_id\":\""+command+"\"";
        for(String input:List.of(prefix+",\"stage_handle\":\""+CodeIntentFixtureSupport.handle()+"\"}",
                prefix+",\"verified\":true}",prefix+",\"command_id\":\""+command+"\"}",
                "{\"experiment_handle\":\""+handle+"\"}","{\"experiment_handle\":\""+handle+"\",\"command_id\":null}",
                "{\"experiment_handle\":\"bad\",\"command_id\":\""+command+"\"}",
                "{\"experiment_handle\":\""+handle+"\",\"command_id\":\"NOT-A-UUID\"}")){
            try(var response=resource().control("bind-stage",headers(CodeIntentFixtureSupportTest.CONTROL,false),body(input))){
                assertEquals(400,response.getStatus());assertNull(response.getEntity());
            }
        }
        assertEquals(0,h.peer.calls);assertEquals(0,h.production.calls);assertEquals("READY",h.support.state(handle));
    }}
    private static CodeIntentFixtureResource resource(){return new CodeIntentFixtureResource(null,CodeIntentFixtureBodyDeadline.create(new java.util.concurrent.Semaphore(1),System::nanoTime,()->{},(millis,task)->()->{}));}
    private static InputStream body(String input){return new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));}
    private static Set<String> fieldNames(com.fasterxml.jackson.databind.JsonNode input){var fields=new HashSet<String>();input.fieldNames().forEachRemaining(fields::add);return fields;}
    private static byte[] publishedBody(java.net.http.HttpRequest request)throws Exception{
        var result=new java.util.concurrent.CompletableFuture<byte[]>();var bytes=new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>(){
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
            public void onNext(java.nio.ByteBuffer item){byte[] value=new byte[item.remaining()];item.get(value);bytes.writeBytes(value);}
            public void onError(Throwable failure){result.completeExceptionally(failure);}
            public void onComplete(){result.complete(bytes.toByteArray());}
        });
        return result.get(2,java.util.concurrent.TimeUnit.SECONDS);
    }
}
