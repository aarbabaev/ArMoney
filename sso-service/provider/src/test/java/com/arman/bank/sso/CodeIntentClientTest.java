package com.arman.bank.sso;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CodeIntentClientTest {
    private static final String WRITER="w".repeat(40),LOOKUP="l".repeat(40),CONFIRM="c".repeat(40);
    private static CodeIntentClient client(CodeIntentClient.Transport transport) {
        var env=new HashMap<String,String>();
        env.put("ARMONEY_LOGIN_PROOF_ENABLED","true"); env.put("SSO_ISSUER",LoginProofTest.BINDING.issuer());
        env.put("ARMONEY_SSO_MANAGED_PAIRS","[{\"issuer\":\""+LoginProofTest.BINDING.issuer()+"\",\"client_id\":\"armoney-web\"}]");
        env.put("ARMONEY_SSO_LEGACY_PAIRS","[{\"issuer\":\""+LoginProofTest.BINDING.issuer()+"\",\"client_id\":\"armoney-ios\"}]");
        env.put("SSO_VERIFICATION_CLIENT_IDS","armoney-web"); env.put("ARMONEY_AUTH_BRIDGE_URL","http://auth-service:8081");
        env.put("SSO_LOGIN_PROOF_WRITE_KEY",WRITER); env.put("SSO_LOGIN_PROOF_CONFIRM_KEY",CONFIRM); env.put("SSO_CODE_INTENT_LOOKUP_KEY",LOOKUP);
        env.put("ARMONEY_PROVIDER_BINDING_KEY","b".repeat(40)); env.put("SSO_LOGIN_PROOF_WRITER_ID","provider");
        env.put("SSO_CODE_INTENT_ACTORS","provider"); env.put("SSO_CODE_INTENT_CODE_KEY_IDS","current,previous");
        var configuration=CodeIntentConfiguration.from(env,Map.of("current",LoginProofTest.keys().keys().getFirst().bytes(),"previous",LoginProofTest.keys().keys().get(1).bytes()));
        return new CodeIntentClient(transport,WRITER,LOOKUP,CONFIRM,Clock.fixed(LoginProofTest.NOW,ZoneOffset.UTC),configuration);
    }
    private static com.fasterxml.jackson.databind.JsonNode json(String value) {
        try { return Bridge.strictJson(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch(Exception bad) { throw new AssertionError(bad); }
    }
    @Test void stageUsesExactOriginalWriteAndChecksFiveFieldAck() {
        var write=LoginProofTest.write(); var count=new AtomicInteger();
        var client=client((operation,fields,key,deadline)->{
            count.incrementAndGet(); assertEquals(CodeIntentHttp.Operation.STAGE,operation); assertEquals(WRITER,key);
            assertEquals(write.fields(),fields); assertEquals(13,fields.size()); deadline.requireOpen();
            return json("{\"intent_id\":\""+LoginProofTest.PROOF+"\",\"code_key_id\":\"current\",\"state\":\"STAGED\",\"issued_at\":\""+write.issued()+"\",\"expires_at\":\""+write.expires()+"\"}");
        });
        try(var deadline=new InvocationDeadline(()->0L)) { assertEquals("current",client.stage(write,deadline).keyId()); }
        assertEquals(1,count.get());
        var bad=client((o,f,k,d)->json("{\"intent_id\":\""+LoginProofTest.PROOF+"\",\"code_key_id\":\"other\",\"state\":\"STAGED\",\"issued_at\":\""+write.issued()+"\",\"expires_at\":\""+write.expires()+"\"}"));
        assertThrows(CodeIntentHttp.Failure.class,()->bad.stage(write,new InvocationDeadline(()->0L)));
    }
    @Test void lookupThenOneConfirmSharesOwnerAndUsesOnlyOfferedPreviousKey() {
        List<CodeIntentHttp.Operation> operations=new ArrayList<>(); var owner=new InvocationDeadline(()->0L);
        var client=client((op,fields,key,deadline)->{
            assertSame(owner,deadline); operations.add(op);
            if(op==CodeIntentHttp.Operation.KEY) {
                assertEquals(LOOKUP,key); assertEquals(5,fields.size()); assertEquals(2,((List<?>)fields.get("candidates")).size());
                return json("{\"code_key_id\":\"previous\"}");
            }
            assertEquals(CONFIRM,key); assertEquals(6,fields.size()); assertEquals("previous",fields.get("code_key_id"));
            assertEquals(LoginProofTest.keys().digest(LoginProofTest.keys().keys().get(1),LoginProofTest.BINDING,LoginProofTest.CODE),fields.get("code_digest"));
            return json(LoginProofTest.reply(false));
        });
        assertTrue(client.lookupAndConfirm(LoginProofTest.keys(),LoginProofTest.BINDING,LoginProofTest.CODE,LoginProofTest.EXPIRY,owner).eligible());
        assertEquals(List.of(CodeIntentHttp.Operation.KEY,CodeIntentHttp.Operation.CONFIRM),operations); owner.close();
    }
    @Test void spentLookupNeverStartsConfirmationAndNoRetryOnFailure() {
        var nanos=new AtomicLong(); var calls=new AtomicInteger();
        var client=client((o,f,k,d)->{ calls.incrementAndGet(); nanos.set(4_000_000_000L); return json("{\"code_key_id\":\"current\"}"); });
        assertThrows(CodeIntentHttp.Failure.class,()->client.lookupAndConfirm(LoginProofTest.keys(),LoginProofTest.BINDING,LoginProofTest.CODE,LoginProofTest.EXPIRY,new InvocationDeadline(nanos::get)));
        assertEquals(1,calls.get());
        calls.set(0);
        var unavailable=client((o,f,k,d)->{ calls.incrementAndGet(); throw new IllegalStateException("private-body"); });
        var failure=assertThrows(CodeIntentHttp.Failure.class,()->unavailable.lookupAndConfirm(LoginProofTest.keys(),LoginProofTest.BINDING,LoginProofTest.CODE,LoginProofTest.EXPIRY,new InvocationDeadline(()->0L)));
        assertEquals(1,calls.get()); assertNull(failure.getCause()); assertFalse(failure.toString().contains("private-body"));
    }
    @Test void unofferedOrMalformedSelectionNeverConfirms() {
        for(String reply:List.of("{\"code_key_id\":\"unknown\"}","{\"code_key_id\":null}","{\"code_key_id\":\"current\",\"extra\":true}")) {
            var count=new AtomicInteger(); var client=client((o,f,k,d)->{count.incrementAndGet();return json(reply);});
            assertThrows(CodeIntentHttp.Failure.class,()->client.lookupAndConfirm(LoginProofTest.keys(),LoginProofTest.BINDING,LoginProofTest.CODE,LoginProofTest.EXPIRY,new InvocationDeadline(()->0L)));
            assertEquals(1,count.get());
        }
    }
    @Test void legacyUnknownAndCrossedBindingNeverFanOut() {
        var calls=new AtomicInteger(); var client=client((o,f,k,d)->{calls.incrementAndGet();throw new AssertionError("Unexpected transport");});
        for(var binding:List.of(new LoginProof.Binding(LoginProofTest.BINDING.issuer(),"subject","armoney-ios","session"),
                new LoginProof.Binding(LoginProofTest.BINDING.issuer(),"subject","armoney-android","session"),
                new LoginProof.Binding("https://other.example/sso/realms/armoney","subject","armoney-web","session"))) {
            var original=LoginProofTest.write();
            var write=new LoginProof.Write(original.command(),original.digest(),original.keyId(),binding,original.authenticationSession(),original.issued(),original.expires(),original.kind(),original.event(),original.passwordTime());
            assertThrows(CodeIntentHttp.Failure.class,()->client.stage(write,new InvocationDeadline(()->0L)));
            assertThrows(CodeIntentHttp.Failure.class,()->client.lookupAndConfirm(LoginProofTest.keys(),binding,LoginProofTest.CODE,LoginProofTest.EXPIRY,new InvocationDeadline(()->0L)));
        }
        assertEquals(0,calls.get());
    }
    @Test void confirmationSnapshotMustBeExactInteractiveEligibleAndOriginalExpiry() {
        for(String reply:List.of(LoginProofTest.reply(false).replace("true","false"),LoginProofTest.reply(false).replace(LoginProofTest.EXPIRY.toString(),LoginProofTest.EXPIRY.plusSeconds(1).toString()),
                LoginProofTest.reply(false).replace("\"kind\":\"interactive\"","\"kind\":\"interactive\",\"extra\":1"))) {
            var calls=new AtomicInteger(); var client=client((o,f,k,d)->{calls.incrementAndGet();return json(o==CodeIntentHttp.Operation.KEY?"{\"code_key_id\":\"current\"}":reply);});
            assertThrows(CodeIntentHttp.Failure.class,()->client.lookupAndConfirm(LoginProofTest.keys(),LoginProofTest.BINDING,LoginProofTest.CODE,LoginProofTest.EXPIRY,new InvocationDeadline(()->0L)));
            assertEquals(2,calls.get());
        }
    }
}
