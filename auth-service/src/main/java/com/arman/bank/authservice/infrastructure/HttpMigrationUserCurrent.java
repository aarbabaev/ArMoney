package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.MigrationUserCurrentPort;
import com.arman.bank.authservice.domain.LiveUserRevision;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.io.ByteArrayOutputStream;

/** Authenticated User PRIMARY observation only. No lease/clock authority; caller retains its original DB anchor.
 * HTTP cancellation does not establish physical DNS termination. No startup probe or default activation. */
public final class HttpMigrationUserCurrent implements MigrationUserCurrentPort,AutoCloseable {
    private static final java.util.logging.Logger LOG=java.util.logging.Logger.getLogger(HttpMigrationUserCurrent.class.getName());
    private enum FailureCategory { TRANSPORT,TIMEOUT,INTERRUPTED,PROTOCOL,RESPONSE_PARSE,RESPONSE_BINDING,HTTP_400,HTTP_401,HTTP_403,HTTP_404,HTTP_409,HTTP_410,HTTP_429,HTTP_503,HTTP_OTHER }
    private static FailureCategory statusCategory(int status){return switch(status){
        case 400->FailureCategory.HTTP_400;case 401->FailureCategory.HTTP_401;case 403->FailureCategory.HTTP_403;case 404->FailureCategory.HTTP_404;
        case 409->FailureCategory.HTTP_409;case 410->FailureCategory.HTTP_410;case 429->FailureCategory.HTTP_429;case 503->FailureCategory.HTTP_503;default->FailureCategory.HTTP_OTHER;
    };}
    public static final String PATH="/v1/internal/migrations/legacy/user-current",HEADER="X-ArMoney-Migration-User-Current-Key";
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final MigrationUserRevisionConfiguration configuration;private final URI endpoint;private final HttpClient http;private volatile boolean closed;
    private final Set<CompletableFuture<?>> pendingRequests=ConcurrentHashMap.newKeySet();private final Set<BoundedBody> bodies=ConcurrentHashMap.newKeySet();
    public static Optional<HttpMigrationUserCurrent> open(MigrationUserRevisionConfiguration c){Objects.requireNonNull(c);return c.enabled()?Optional.of(new HttpMigrationUserCurrent(c)):Optional.empty();}
    private HttpMigrationUserCurrent(MigrationUserRevisionConfiguration c){this(c,c.userOrigin(),HttpCodeSessionObservations.privateClient());}
    /** Isolated loopback fixture only; owns supplied client. */
    HttpMigrationUserCurrent(MigrationUserRevisionConfiguration c,URI origin,HttpClient client){
        if(!c.enabled()||origin==null||client.followRedirects()!=HttpClient.Redirect.NEVER||!client.connectTimeout().orElse(Duration.ZERO).equals(Duration.ofSeconds(2)))throw new IllegalArgumentException("User client rejected");
        boolean production=origin.equals(c.userOrigin());boolean fixture="http".equals(origin.getScheme())&&"127.0.0.1".equals(origin.getHost())&&origin.getPort()>0&&origin.getPort()<=65535;
        if((!production&&!fixture)||origin.getRawUserInfo()!=null||origin.getRawQuery()!=null||origin.getRawFragment()!=null||!(origin.getRawPath().isEmpty()||origin.getRawPath().equals("/")))throw new IllegalArgumentException("User client rejected");
        configuration=c;endpoint=origin.resolve(PATH);http=client;
    }
    @Override public Observation observe(Query query,Duration originalRemainingBudget){
        long started=System.nanoTime();long budget;try{budget=originalRemainingBudget.toNanos();if(budget<=0||budget>4_000_000_000L)throw unavailable();}catch(RuntimeException e){throw unavailable();}
        if(closed||Thread.currentThread().isInterrupted())throw unavailable();CompletableFuture<HttpResponse<byte[]>> pending=null;var body=new BoundedBody();
        FailureCategory failureCategory=FailureCategory.TRANSPORT;
        try{
            var request=LiveUserRevision.current(configuration.currentWriter(),query.operationId(),query.identityId(),query.migrationId(),query.decisionId(),query.classificationGeneration(),query.epoch(),query.sourceRevision());
            byte[] bytes=JSON.writeValueAsBytes(request);if(bytes.length>4096)throw unavailable();remaining(started,budget);bodies.add(body);if(closed)throw unavailable();
            pending=http.sendAsync(HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(remaining(started,budget))).header("Content-Type","application/json").header(HEADER,configuration.currentKey()).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),info->body);
            pendingRequests.add(pending);body.attach(pending);if(closed)throw unavailable();
            var response=pending.get(remaining(started,budget),TimeUnit.NANOSECONDS);remaining(started,budget);
            failureCategory=response.statusCode()==200?FailureCategory.PROTOCOL:statusCategory(response.statusCode());
            if(response.statusCode()!=200||!response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().equalsIgnoreCase("application/json"))throw unavailable();
            failureCategory=FailureCategory.RESPONSE_PARSE;
            var parsed=parse(response.body());
            failureCategory=FailureCategory.RESPONSE_BINDING;
            var decoded=LiveUserRevision.currentResponse(request,parsed);remaining(started,budget);
            return new Observation(query,LiveUserRevision.object(decoded.get("current"),false));
        }catch(Exception e){
            if(e instanceof InterruptedException){failureCategory=FailureCategory.INTERRUPTED;Thread.currentThread().interrupt();}
            else if(e instanceof TimeoutException||e instanceof HttpTimeoutException||e instanceof ExecutionException execution&&execution.getCause() instanceof HttpTimeoutException)failureCategory=FailureCategory.TIMEOUT;
            LOG.warning("armoney_auth_user_current_failure category="+failureCategory.name());throw unavailable();
        }
        finally{if(pending!=null)pending.cancel(true);body.abort();bodies.remove(body);if(pending!=null)pendingRequests.remove(pending);}
    }
    static Map<String,Object> parse(byte[] bytes){
        if(bytes==null||bytes.length>4096)throw unavailable();
        try(var parser=JSON.getFactory().createParser(bytes)){
            while(parser.nextToken()!=null)if(parser.currentToken().isNumeric()){
                if(parser.currentToken()!=JsonToken.VALUE_NUMBER_INT||!parser.getText().matches("0|[1-9][0-9]*"))throw unavailable();parser.getLongValue();}
            Map<String,Object> result=JSON.readValue(bytes,new TypeReference<LinkedHashMap<String,Object>>(){});if(result==null)throw unavailable();return result;
        }catch(Exception e){throw unavailable();}
    }
    static long remaining(long start,long budget){long elapsed=System.nanoTime()-start;if(elapsed<0||elapsed>=budget||Thread.currentThread().isInterrupted())throw unavailable();return budget-elapsed;}
    private static IllegalStateException unavailable(){return new IllegalStateException("User current observation unavailable");}
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;private CompletableFuture<?> transport;private boolean ended,failed;
        @Override public CompletionStage<byte[]> getBody(){return result;}
        synchronized void attach(CompletableFuture<?> future){transport=future;if(failed){transport.cancel(true);result.completeExceptionally(unavailable());}}
        @Override public synchronized void onSubscribe(Flow.Subscription s){if(ended||subscription!=null){s.cancel();return;}subscription=s;s.request(1);}
        @Override public synchronized void onNext(List<ByteBuffer> items){if(ended)return;try{for(ByteBuffer item:items){if(item.remaining()>4096-bytes.size()){abort();return;}byte[] part=new byte[item.remaining()];item.get(part);bytes.writeBytes(part);}subscription.request(1);}catch(RuntimeException e){abort();}}
        @Override public synchronized void onError(Throwable ignored){abort();}@Override public synchronized void onComplete(){if(!ended){ended=true;result.complete(bytes.toByteArray());bytes.reset();}}
        synchronized void abort(){if(ended)return;ended=true;failed=true;bytes.reset();if(subscription!=null)subscription.cancel();if(transport!=null){transport.cancel(true);result.completeExceptionally(unavailable());}}
    }

    @Override public void close(){closed=true;pendingRequests.forEach(f->f.cancel(true));bodies.forEach(BoundedBody::abort);http.shutdownNow();try{if(!http.awaitTermination(Duration.ofSeconds(2)))throw unavailable();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    @Override public String toString(){return "HttpMigrationUserCurrent[redacted]";}
}
