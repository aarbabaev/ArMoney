package com.arman.bank.authservice.infrastructure;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.arman.bank.authservice.infrastructure.ProductionProviderAcceptanceController.*;

/** Test-only exclusive-writer operation. GET/PUT is NOT atomic compare-and-swap. */
final class ProductionProviderAcceptancePolicy implements AutoCloseable {
    static final String PATH="/sso/admin/realms/armoney/client-policies/policies";
    static final String ALIAS="armoney-code-intent-bank-clients",PROFILE="armoney-code-intent-profile";
    static final String ATTRIBUTES="[{\"key\":\"armoney.code-intent-policy\",\"value\":\"true\"}]";
    interface Peer extends AutoCloseable { JsonNode get(Deadline deadline) throws Exception;void put(JsonNode value,Deadline deadline) throws Exception;default void close(){} }
    private enum State { ORIGINAL,DISABLED,RESTORED,FAILED }
    private final Peer peer;
    private final JsonNode original,disabled;
    private State state=State.ORIGINAL;
    ProductionProviderAcceptancePolicy(Peer peer,Deadline deadline) throws Exception {
        this.peer=Objects.requireNonNull(peer);original=peer.get(deadline).deepCopy();
        JsonNode target=target(original);
        if(!target.path("enabled").isBoolean()||!target.path("enabled").booleanValue()
            ||!target.path("profiles").equals(JSON.valueToTree(List.of(PROFILE))))throw new Rejected(503);
        JsonNode conditions=target.path("conditions");
        if(!conditions.isArray()||conditions.size()!=1||!conditions.get(0).isObject()||conditions.get(0).size()!=2
            ||!"client-attributes".equals(conditions.get(0).path("condition").asText())||!conditions.get(0).path("configuration").isObject()
            ||conditions.get(0).path("configuration").size()!=1)throw new Rejected(503);
        JsonNode attributes=conditions.get(0).path("configuration").path("attributes");
        if(!attributes.isTextual()||attributes.textValue().length()>256)throw new Rejected(503);
        try{
            // Keycloak stores this configuration as a JSON-encoded string. Decode strictly,
            // compare the exact one-attribute structure, and preserve its original representation.
            if(!JSON.readTree(ATTRIBUTES).equals(JSON.readTree(attributes.textValue())))throw new Rejected(503);
        }catch(java.io.IOException malformed){throw new Rejected(503);}
        disabled=original.deepCopy();((ObjectNode)target(disabled)).put("enabled",false);
    }
    private static JsonNode target(JsonNode document){
        if(document==null||!document.isObject()||!document.path("policies").isArray()||document.path("policies").size()>64)throw new Rejected(503);
        JsonNode found=null;for(JsonNode policy:document.path("policies")){
            if(!policy.isObject())throw new Rejected(503);
            if(ALIAS.equals(policy.path("name").asText())){if(found!=null)throw new Rejected(503);found=policy;}
        }
        if(found==null)throw new Rejected(503);return found;
    }
    synchronized void remove(Deadline deadline) throws Exception {
        if(state==State.FAILED||state==State.RESTORED)throw new Rejected(503);
        if(state==State.DISABLED){verify(disabled,deadline);return;}
        transition(original,disabled,State.DISABLED,deadline);
    }
    synchronized void restore(Deadline deadline) throws Exception {
        if(state==State.FAILED||state==State.ORIGINAL)throw new Rejected(503);
        if(state==State.RESTORED){verify(original,deadline);return;}
        transition(disabled,original,State.RESTORED,deadline);
    }
    private void transition(JsonNode expected,JsonNode replacement,State next,Deadline deadline) throws Exception {
        try{verify(expected,deadline);deadline.remaining();peer.put(replacement.deepCopy(),deadline);verify(replacement,deadline);state=next;}
        catch(Exception failure){state=State.FAILED;throw new Rejected(503);} // Unknown commit never triggers a fallback write.
    }
    private void verify(JsonNode expected,Deadline deadline) throws Exception {
        try{deadline.remaining();if(!expected.equals(peer.get(deadline)))throw new Rejected(503);deadline.remaining();}
        catch(Exception failure){state=State.FAILED;throw new Rejected(503);}
    }
    @Override public void close(){try{peer.close();}catch(Exception ignored){/* No implicit policy mutation on cleanup. */}}
    @Override public String toString(){return "ProductionProviderAcceptancePolicy[redacted]";}

    static final class AdminPeer implements Peer {
        private final HttpClient client;
        private final URI origin;
        private final String clientSecret;
        private final Set<java.util.concurrent.CompletableFuture<?>> pending=java.util.concurrent.ConcurrentHashMap.newKeySet();
        AdminPeer(URI origin,String clientSecret){
            if(!URI.create("http://sso-service:8080").equals(origin))throw new Rejected(503);
            this.origin=origin;this.clientSecret=Objects.requireNonNull(clientSecret);
            client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
        }
        private String token(Deadline deadline) throws Exception {
            String form="grant_type=client_credentials&client_id=armoney-production-acceptance-admin&client_secret="+java.net.URLEncoder.encode(clientSecret,StandardCharsets.UTF_8);
            JsonNode result=exchange("/sso/realms/armoney/protocol/openid-connect/token",null,form.getBytes(StandardCharsets.UTF_8),"application/x-www-form-urlencoded",deadline,200);
            if(!result.path("access_token").isTextual()||result.path("access_token").textValue().length()>16384)throw new Rejected(503);
            return result.path("access_token").textValue();
        }
        public JsonNode get(Deadline deadline) throws Exception{return exchange(PATH,token(deadline),null,null,deadline,200);}
        public void put(JsonNode value,Deadline deadline) throws Exception{exchange(PATH,token(deadline),JSON.writeValueAsBytes(value),"application/json",deadline,204);}
        private JsonNode exchange(String path,String token,byte[] body,String contentType,Deadline deadline,int expected) throws Exception {
            var bytes=new ProductionProviderAcceptanceTransport.Bytes(65536);
            var builder=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofNanos(deadline.remaining()));
            if(token!=null)builder.header("Authorization","Bearer "+token);
            if(body==null)builder.GET();else builder.header("Content-Type",contentType).method(expected==204?"PUT":"POST",HttpRequest.BodyPublishers.ofByteArray(body));
            var request=client.sendAsync(builder.build(),ignored->bytes);pending.add(request);byte[] response=null;
            try{
                var result=request.get(deadline.remaining(),TimeUnit.NANOSECONDS);response=result.body();
                if(result.statusCode()!=expected)throw new Rejected(503);
                if(expected==204){if(response.length!=0)throw new Rejected(503);return JSON.createObjectNode();}
                if(!result.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().equalsIgnoreCase("application/json"))throw new Rejected(503);
                return JSON.readTree(ProductionProviderAcceptanceTransport.strictUtf8(response));
            }finally{pending.remove(request);request.cancel(true);bytes.cancel();if(body!=null)Arrays.fill(body,(byte)0);if(response!=null)Arrays.fill(response,(byte)0);}
        }
        @Override public void close(){pending.forEach(p->p.cancel(true));client.shutdownNow();}
        @Override public String toString(){return "ProductionAcceptanceAdminPeer[redacted]";}
    }
}
