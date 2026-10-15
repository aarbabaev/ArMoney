package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import jakarta.ws.rs.core.Response;
import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

final class LoginProofTest {
    static final String CODE="a1111111-1111-4111-8111-111111111111", EVENT="b1111111-1111-4111-8111-111111111111", PROOF="c1111111-1111-4111-8111-111111111111";
    static final Instant NOW=Instant.parse("2026-10-15T10:00:00Z"), EXPIRY=NOW.plusSeconds(60);
    static final LoginProof.Binding BINDING=new LoginProof.Binding("https://identity.example/sso/realms/armoney","subject-a","armoney-web","session-a");
    static final String WRITE="synthetic-only-write-proof-key-12345678", RESOLVE="synthetic-only-resolve-proof-key-12345678";
    static CodeKeys keys() { byte[] bytes=new byte[32]; for(int i=0;i<32;i++)bytes[i]=(byte)i; return new CodeKeys(new CodeKeys.Key("current",bytes),new CodeKeys.Key("previous",new byte[32])); }
    static LoginProof.Write write() { var ring=keys(); return new LoginProof.Write(UUID.fromString(PROOF),ring.digest(ring.keys().getFirst(),BINDING,CODE),"current",BINDING,"opaque-binding",NOW,EXPIRY,"interactive",UUID.fromString(EVENT),NOW.minusSeconds(4)); }
    static String reply(boolean write) {
        return "{\"proof_id\":\""+PROOF+"\",\"event_id\":\""+EVENT+"\",\"kind\":\"interactive\",\"login_notice_eligible\":true,\"issued_at\":\""+NOW+"\",\"expires_at\":\""+EXPIRY+"\""
                +(write?",\"code_key_id\":\"current\",\"state\":\"RECORDED\"":"")+"}";
    }
    @Test void digestMatchesIndependentDotNetHmacFixtureAndSeparatesCodeAndKey() {
        var ring=keys(); String digest=ring.digest(ring.keys().getFirst(),BINDING,CODE);
        assertEquals("7e5bb9463854e899859ae9b1ca92f336a81a42b8f207e1b66c04c20b2e0cf130",digest);
        assertNotEquals(digest,ring.digest(ring.keys().getLast(),BINDING,CODE));
        assertNotEquals(digest,ring.digest(ring.keys().getFirst(),BINDING,EVENT));
        assertThrows(IllegalArgumentException.class,()->ring.digest(ring.keys().getFirst(),BINDING,"incoming-short-id"));
    }
    @Test void keyRingRejectsInvalidBase64LengthAndDuplicateIds() {
        assertThrows(IllegalArgumentException.class,()->CodeKeys.Key.fromBase64("current","invalid"));
        assertThrows(IllegalArgumentException.class,()->new CodeKeys.Key("current",new byte[31]));
        assertThrows(IllegalArgumentException.class,()->new CodeKeys(keys().keys().getFirst(),keys().keys().getFirst()));
        var encoded=Base64.getEncoder().encodeToString(new byte[32]);
        assertThrows(IllegalArgumentException.class,()->CodeKeys.Key.fromBase64("current",encoded.replace("=","")));
    }
    @Test void onlyExactPlainCodeQueryModesAreSupported() {
        ProofLoginProtocol.requireQueryCode("code",null); ProofLoginProtocol.requireQueryCode("code","query");
        for(String type:List.of("token","code id_token","CODE","code ",""))
            assertThrows(IllegalArgumentException.class,()->ProofLoginProtocol.requireQueryCode(type,null));
        for(String mode:List.of("fragment","form_post","query.jwt","", "QUERY"))
            assertThrows(IllegalArgumentException.class,()->ProofLoginProtocol.requireQueryCode("code",mode));
    }
    @Test void generatedRedirectMustContainOneCodeBoundToServerSessionAndClient() {
        String valid="https://callback.example/oauth?state=unchanged&code="+CODE+".session-a.client-internal";
        try(var response=Response.status(302).location(URI.create(valid)).build()) {
            assertEquals(CODE,ProofLoginProtocol.codeId(response,"session-a","client-internal"));
            assertThrows(ProofClient.Failure.class,()->ProofLoginProtocol.codeId(response,"another-session","client-internal"));
        }
        for(String uri:List.of(valid+"&code="+CODE+".session-a.client-internal",valid+"#fragment","https://callback.example/?state=x"))
            try(var response=Response.status(302).location(URI.create(uri)).build()) {
                assertThrows(ProofClient.Failure.class,()->ProofLoginProtocol.codeId(response,"session-a","client-internal"));
            }
    }
    @Test void writeHasSeparateCredentialStrictEchoAndNoRedeemableCode() {
        var transport=new Transport(new Reply(200,reply(true))); var client=client(transport);
        assertTrue(client.write(write()).eligible()); var sent=transport.requests.getFirst();
        assertEquals("/internal/sso-login-proofs",sent.uri().getPath());
        assertEquals(WRITE,sent.headers().firstValue("X-ArMoney-Login-Proof-Write-Key").orElseThrow());
        assertTrue(sent.headers().firstValue("X-ArMoney-Login-Proof-Resolve-Key").isEmpty());
        assertEquals(Duration.ofSeconds(4),sent.timeout().orElseThrow());
        var fields=write().fields(); assertFalse(fields.containsKey("code")); assertFalse(fields.containsKey("canonical_code_data_id"));
        assertNull(fields.get("email")); assertEquals(NOW.minusSeconds(4).toString(),fields.get("password_authenticated_at"));
        assertFalse(write().toString().contains("subject-a"));
    }
    @Test void previousKeyIsTriedOnlyAfterExplicitCurrentAbsence() {
        var transport=new Transport(new Reply(404,"{}"),new Reply(200,reply(false)));
        assertEquals(UUID.fromString(EVENT),client(transport).resolve(keys(),BINDING,CODE,EXPIRY,NOW).event());
        assertEquals(2,transport.requests.size());
        for(var sent:transport.requests) {
            assertEquals(RESOLVE,sent.headers().firstValue("X-ArMoney-Login-Proof-Resolve-Key").orElseThrow());
            assertTrue(sent.headers().firstValue("X-ArMoney-Login-Proof-Write-Key").isEmpty());
        }
    }
    @Test void conflictExpiryOutageMalformedAndAmbiguousReplyNeverDowngradeToPreviousKey() {
        for(int status:List.of(301,401,409,410,429,503)) {
            var transport=new Transport(new Reply(status,"{}"),new Reply(200,reply(false)));
            assertThrows(ProofClient.Failure.class,()->client(transport).resolve(keys(),BINDING,CODE,EXPIRY,NOW));
            assertEquals(1,transport.requests.size());
        }
        for(String body:List.of(reply(false)+"{}",reply(false).replace("true","\"true\""),
                reply(false).replace("\"kind\":","\"unknown\":0,\"kind\":"),reply(false).replace(EVENT,"not-uuid"))) {
            var transport=new Transport(new Reply(200,body),new Reply(200,reply(false)));
            assertThrows(RuntimeException.class,()->client(transport).resolve(keys(),BINDING,CODE,EXPIRY,NOW));
            assertEquals(1,transport.requests.size());
        }
    }
    @Test void immutableExpiryAndWriteEventMustMatchAndRepliesMustBeNoStore() {
        assertThrows(ProofClient.Failure.class,()->client(new Transport(new Reply(200,reply(false)))).resolve(keys(),BINDING,CODE,EXPIRY,NOW.plusSeconds(60)));
        assertThrows(ProofClient.Failure.class,()->client(new Transport(new Reply(200,reply(false)))).resolve(keys(),BINDING,CODE,EXPIRY.plusSeconds(1),NOW));
        assertThrows(IllegalArgumentException.class,()->client(new Transport(new Reply(200,reply(true).replace(EVENT,CODE)))).write(write()));
        var transport=new Transport(new Reply(200,reply(true))); transport.noStore=false;
        assertThrows(ProofClient.Failure.class,()->client(transport).write(write()));
    }
    @Test void introspectionKeepsOriginalTokenAClaimsDespiteLaterEventB() {
        Map<String,Object> original=new HashMap<>(Map.of(ProofClaims.EVENT,EVENT,ProofClaims.KIND,"interactive",ProofClaims.ELIGIBLE,true));
        Map<String,Object> later=new HashMap<>(Map.of(ProofClaims.EVENT,CODE,ProofClaims.KIND,"interactive",ProofClaims.ELIGIBLE,true,"unrelated","kept"));
        ProofClaims.preserve(original,later);
        assertEquals(EVENT,later.get(ProofClaims.EVENT)); assertEquals("kept",later.get("unrelated"));
        assertEquals(EVENT,original.get(ProofClaims.EVENT));
        ProofClaims.clear(later); assertFalse(later.containsKey(ProofClaims.ELIGIBLE)); assertEquals("kept",later.get("unrelated"));
    }
    @Test void malformedOrAbsentSignedClaimsCannotBecomeEligible() {
        for(Map<String,Object> input:List.of(Map.<String,Object>of(),Map.<String,Object>of(ProofClaims.KIND,"reuse",ProofClaims.ELIGIBLE,true),
                Map.<String,Object>of(ProofClaims.KIND,"interactive",ProofClaims.ELIGIBLE,true),
                Map.<String,Object>of(ProofClaims.EVENT,EVENT,ProofClaims.KIND,"interactive",ProofClaims.ELIGIBLE,"true"))) {
            var output=new HashMap<String,Object>(Map.of(ProofClaims.EVENT,CODE,ProofClaims.KIND,"interactive",ProofClaims.ELIGIBLE,true));
            ProofClaims.preserve(input,output); assertTrue(output.isEmpty());
        }
        var output=new HashMap<String,Object>(); ProofClaims.preserve(Map.of(ProofClaims.KIND,"reuse",ProofClaims.ELIGIBLE,false),output);
        assertEquals(Map.of(ProofClaims.KIND,"reuse",ProofClaims.ELIGIBLE,false),output);
    }
    @Test void writeRejectsInventedPasswordTimeAndUnsupportedCodeLifetimes() {
        var valid=write();
        assertThrows(IllegalArgumentException.class,()->new LoginProof.Write(valid.command(),valid.digest(),valid.keyId(),BINDING,"binding",NOW,EXPIRY,"interactive",valid.event(),NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->new LoginProof.Write(valid.command(),valid.digest(),valid.keyId(),BINDING,"binding",NOW,NOW.plusSeconds(121),"reuse",null,null));
        assertThrows(IllegalArgumentException.class,()->new LoginProof.Write(valid.command(),valid.digest(),valid.keyId(),BINDING,"binding",NOW,EXPIRY,"reuse",valid.event(),null));
    }
    static ProofClient client(Transport transport) { return new ProofClient(URI.create("http://auth-service:8081"),WRITE,RESOLVE,transport); }
    record Reply(int status,String body) { }
    /** Synthetic transport, not evidence of Keycloak callbacks or network operation. */
    static final class Transport extends HttpClient {
        final Queue<Reply> replies; final List<HttpRequest> requests=new ArrayList<>(); boolean noStore=true;
        Transport(Reply... replies) { this.replies=new ArrayDeque<>(List.of(replies)); }
        @Override public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler) {
            requests.add(request); Reply reply=replies.remove();
            var info=new HttpResponse.ResponseInfo() {
                public int statusCode(){return reply.status;} public HttpHeaders headers(){return HttpHeaders.of(noStore?Map.of("Cache-Control",List.of("no-store")):Map.of(),(a,b)->true);}
                public Version version(){return Version.HTTP_1_1;}
            };
            var subscriber=handler.apply(info); subscriber.onSubscribe(new Flow.Subscription(){public void request(long n){} public void cancel(){}});
            subscriber.onNext(List.of(ByteBuffer.wrap(reply.body.getBytes(StandardCharsets.UTF_8)))); subscriber.onComplete();
            T body=subscriber.getBody().toCompletableFuture().join();
            return new HttpResponse<>() {
                public int statusCode(){return reply.status;} public HttpRequest request(){return request;}
                public Optional<HttpResponse<T>> previousResponse(){return Optional.empty();} public HttpHeaders headers(){return info.headers();}
                public T body(){return body;} public Optional<SSLSession> sslSession(){return Optional.empty();}
                public URI uri(){return request.uri();} public Version version(){return Version.HTTP_1_1;}
            };
        }
        public Optional<CookieHandler> cookieHandler(){return Optional.empty();} public Optional<Duration> connectTimeout(){return Optional.of(Duration.ofSeconds(2));}
        public Redirect followRedirects(){return Redirect.NEVER;} public Optional<ProxySelector> proxy(){return Optional.empty();}
        public SSLContext sslContext(){throw new UnsupportedOperationException();} public SSLParameters sslParameters(){return new SSLParameters();}
        public Optional<Authenticator> authenticator(){return Optional.empty();} public Version version(){return Version.HTTP_1_1;}
        public Optional<Executor> executor(){return Optional.empty();}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h){
            try{return CompletableFuture.completedFuture(send(r,h));}catch(Exception failure){return CompletableFuture.failedFuture(failure);}
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){throw new UnsupportedOperationException();}
    }
}
