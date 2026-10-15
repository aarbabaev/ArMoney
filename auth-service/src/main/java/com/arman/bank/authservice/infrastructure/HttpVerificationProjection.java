package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.VerificationProjectionStore.Transport;
import com.arman.bank.authservice.domain.VerificationProjection;
import com.arman.bank.authservice.domain.VerificationProjection.*;
import com.fasterxml.jackson.databind.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed auth-to-user destination, no redirects/retries; caller persists uncertainty. */
public final class HttpVerificationProjection implements Transport {
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient client;
    private final URI endpoint;
    private final String credential;
    private volatile boolean closed;
    public HttpVerificationProjection(URI base,String credential) {
        origin(base); credential(credential); this.credential=credential;
        endpoint=base.resolve("/v1/internal/verification-projections");
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    static void origin(URI base) {
        if(base==null || !("http".equals(base.getScheme())||"https".equals(base.getScheme())) || base.getHost()==null
                || base.getUserInfo()!=null || base.getRawQuery()!=null || base.getRawFragment()!=null
                || !(base.getRawPath().isEmpty()||base.getRawPath().equals("/"))) throw new IllegalArgumentException("Invalid projection origin");
    }
    static void credential(String value) {
        if(value==null || value.length()<32 || value.length()>256 || value.chars().anyMatch(c->c<33||c>126)) throw new IllegalArgumentException("Invalid projection credential");
    }
    public static byte[] body(Command command) {
        VerificationProjection.validate(command);
        var fields=new LinkedHashMap<String,Object>();
        fields.put("operation_id",command.operationId().toString()); fields.put("identity_id",command.identityId().toString());
        fields.put("expected_version",command.expectedVersion()); fields.put("version",command.version()); fields.put("canonical_phone",command.canonicalPhone());
        fields.put("phone_contact_version",command.phoneContactVersion()); fields.put("verification_epoch",command.verificationEpoch()); fields.put("payload_hash",command.payloadHash());
        try{return JSON.writeValueAsBytes(fields);}catch(java.io.IOException invalid){throw new IllegalStateException("Projection encoding unavailable");}
    }
    @Override public Result send(Claim claim) throws InterruptedException {
        if(closed || claim==null) return Result.retry();
        byte[] bytes=claim.requestBody(), responseBytes=null;
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        var status=new AtomicInteger();
        try {
            if(!Arrays.equals(bytes,body(claim.command()))) return new Result(Outcome.BAD_REQUEST,null);
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(4)).header("Content-Type","application/json")
                .header("X-ArMoney-Verification-Projection-Key",credential).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            pending=client.sendAsync(request,info->{status.set(info.statusCode());return new BoundedBody();});
            var response=pending.get(4,TimeUnit.SECONDS); responseBytes=response.body();
            if(response.statusCode()!=200) return rejection(response.statusCode());
            if(!response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().equalsIgnoreCase("application/json")) return Result.retry();
            var ack=JSON.readTree(responseBytes);
            if(ack==null || !ack.isObject() || ack.size()!=6 || !ack.has("operation_id") || !ack.has("identity_id") || !ack.has("version")
                    || !ack.has("phone_contact_version") || !ack.has("verification_epoch") || !ack.has("payload_hash")) return Result.retry();
            var result=new Ack(uuid(ack,"operation_id"),uuid(ack,"identity_id"),integer(ack,"version"),integer(ack,"phone_contact_version"),epoch(ack),text(ack,"payload_hash"));
            return result.matches(claim.command())?new Result(Outcome.ACK,result):Result.retry();
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw interrupted;}
        catch(Exception unavailable) {return rejection(status.get());}
        finally {
            if(pending!=null && !pending.isDone()) pending.cancel(true);
            Arrays.fill(bytes,(byte)0); if(responseBytes!=null) Arrays.fill(responseBytes,(byte)0);
        }
    }
    private static Result rejection(int status) {
        return new Result(switch(status){case 400->Outcome.BAD_REQUEST;case 401->Outcome.UNAUTHORIZED;case 409->Outcome.CONFLICT;case 410->Outcome.EXPIRED;case 429->Outcome.RATE_LIMITED;default->Outcome.RETRY;},null);
    }
    private static String text(JsonNode body,String field) {var value=body.get(field);if(value==null||!value.isTextual()) throw new IllegalArgumentException();return value.textValue();}
    private static UUID uuid(JsonNode body,String field) {
        String value=text(body,field);
        if(!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException();
        return UUID.fromString(value);
    }
    private static int integer(JsonNode body,String field){var value=body.get(field);if(value==null||!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<1)throw new IllegalArgumentException();return value.intValue();}
    private static long epoch(JsonNode body){var value=body.get("verification_epoch");if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<1)throw new IllegalArgumentException();return value.longValue();}
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(var buffer:buffers){if(buffer.remaining()>12288-bytes.size()){subscription.cancel();result.completeExceptionally(new IllegalStateException("Projection response exceeds bound"));return;}
                while(buffer.hasRemaining())bytes.write(buffer.get());}subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(new IllegalStateException("Projection response unavailable"));}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    @Override public void close(){closed=true;client.shutdownNow();}
}
