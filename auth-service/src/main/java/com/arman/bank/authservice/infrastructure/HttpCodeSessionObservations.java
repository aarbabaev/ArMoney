package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.CodeConfirmationObservations;
import com.arman.bank.authservice.domain.CodeConfirmation;
import com.arman.bank.authservice.domain.LoginProof;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Exact postcommit code-session transport only. No Main/resource wiring or provider-runtime proof.
 * The owning PG core independently checks original DB admission/receive/final relative freshness.
 * Cancellation/HTTP timeout does not prove physical DNS cancellation or equivalent clock rates. */
public final class HttpCodeSessionObservations implements CodeConfirmationObservations,AutoCloseable {
    private static final String PATH="/sso/realms/armoney/armoney-status/code-session";
    private static final int LIMIT=12288;
    private static final long BUDGET=TimeUnit.SECONDS.toNanos(4);
    private static final Set<String> FIELDS=Set.of("issuer","subject","client_id","user_session_id","username","email","phone_number",
        "enabled","user_session_active","client_session_active","observed_at","valid_until");
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final URI endpoint;
    private final String credential;
    private final CodeIntentPairScopes scopes;
    private final HttpClient client;
    private record Exchange(CompletableFuture<?> future,BoundedBody body) {void cancel(){body.cancel();future.cancel(true);}}
    private final Set<Exchange> exchanges=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    /** Default false returns before constructing any HTTP client/resource. Caller owns and closes selected adapter. */
    public static Optional<HttpCodeSessionObservations> open(CodeConfirmationConfiguration configuration){
        Objects.requireNonNull(configuration);
        if(!configuration.enabled)return Optional.empty();
        return Optional.of(new HttpCodeSessionObservations(configuration.observerEndpoint(),configuration.statusKey(),configuration.scopes(),
            privateClient(),false));
    }
    /** One production client policy, also used by isolated transport fixtures. Never inherit JVM/system proxies. */
    static HttpClient privateClient(){
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector(){
                @Override public List<Proxy> select(URI ignored){return List.of(Proxy.NO_PROXY);}
                @Override public void connectFailed(URI ignored,SocketAddress address,java.io.IOException failed){}
            }).build();
    }
    /** Isolated loopback HTTP fixture only; owns supplied client. Never selected by environment configuration. */
    HttpCodeSessionObservations(URI origin,String credential,CodeIntentPairScopes scopes,HttpClient client){
        this(fixtureEndpoint(origin),credential,scopes,client,true);
    }
    private static URI fixtureEndpoint(URI origin){HttpVerificationObservations.origin(origin,true);return origin.resolve(PATH);}
    private HttpCodeSessionObservations(URI endpoint,String credential,CodeIntentPairScopes scopes,HttpClient client,boolean fixture){
        HttpVerificationObservations.credential(credential);
        this.endpoint=Objects.requireNonNull(endpoint);this.credential=credential;this.scopes=Objects.requireNonNull(scopes);this.client=Objects.requireNonNull(client);
        if(scopes.managed().isEmpty()||!PATH.equals(endpoint.getRawPath())||client.followRedirects()!=HttpClient.Redirect.NEVER
            ||!client.connectTimeout().orElse(Duration.ZERO).equals(Duration.ofSeconds(2)))throw new IllegalArgumentException("Invalid code session observation configuration");
    }
    @Override public CodeConfirmation.Facts observe(LoginProof.Binding binding,Instant originalDatabaseStart){
        long started=System.nanoTime();
        if(closed||originalDatabaseStart==null||Thread.currentThread().isInterrupted())throw unavailable();
        validate(binding);
        byte[] requestBytes=null,responseBytes=null;Exchange exchange=null;var body=new BoundedBody();
        try{
            var fields=new LinkedHashMap<String,Object>();fields.put("issuer",binding.issuer());fields.put("subject",binding.subject());fields.put("client_id",binding.clientId());fields.put("user_session_id",binding.userSessionId());
            requestBytes=JSON.writeValueAsBytes(fields);if(requestBytes.length>LIMIT)throw unavailable();
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(remaining(started))).header("Content-Type","application/json")
                .header("X-ArMoney-Auth-Status-Key",credential).POST(HttpRequest.BodyPublishers.ofByteArray(requestBytes)).build();
            var pending=client.sendAsync(request,info->body);exchange=new Exchange(pending,body);exchanges.add(exchange);if(closed)exchange.cancel();
            var response=pending.get(remaining(started),TimeUnit.NANOSECONDS);responseBytes=response.body();
            var types=response.headers().allValues("Content-Type");
            if(response.statusCode()!=200||types.size()!=1||!types.getFirst().matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?"))throw unavailable();
            var facts=decode(binding,responseBytes);remaining(started);if(closed)throw unavailable();return facts;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception rejected){throw unavailable();}
        finally{
            body.cancel();if(exchange!=null){exchanges.remove(exchange);if(!exchange.future().isDone())exchange.future().cancel(true);}
            if(requestBytes!=null)Arrays.fill(requestBytes,(byte)0);if(responseBytes!=null)Arrays.fill(responseBytes,(byte)0);
        }
    }
    private void validate(LoginProof.Binding binding){
        if(binding==null||scopes.classify(binding.issuer(),binding.clientId())!=CodeIntentPairScopes.Classification.MANAGED
            ||!bounded(binding.subject(),255)||!bounded(binding.userSessionId(),255))throw unavailable();
    }
    private CodeConfirmation.Facts decode(LoginProof.Binding expected,byte[] bytes)throws Exception{
        if(bytes==null||bytes.length>LIMIT)throw unavailable();
        String source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        var body=JSON.readTree(source);if(body==null||!body.isObject()||body.size()!=FIELDS.size())throw unavailable();
        var names=body.fieldNames();while(names.hasNext())if(!FIELDS.contains(names.next()))throw unavailable();
        var actual=new LoginProof.Binding(text(body,"issuer",512),text(body,"subject",255),text(body,"client_id",128),text(body,"user_session_id",255));
        validate(actual);if(!expected.equals(actual))throw unavailable();
        for(String flag:List.of("enabled","user_session_active","client_session_active"))if(!body.get(flag).isBoolean())throw unavailable();
        String username=text(body,"username",255),email=nullable(body,"email",254),phone=nullable(body,"phone_number",13);
        if(email!=null&&(!email.equals(email.toLowerCase(Locale.ROOT))||!email.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")))throw unavailable();
        if(phone!=null&&!phone.matches("\\+9715[024568][0-9]{7}"))throw unavailable();
        Instant observed=time(body,"observed_at"),valid=time(body,"valid_until");
        long delta=Duration.between(observed,valid).getSeconds();
        if(delta<1||delta>5)throw unavailable();
        // No absolute provider/JVM/DB clock comparison. Delta1 has no usable conservative window.
        if(delta==1)throw unavailable();
        return new CodeConfirmation.Facts(expected,username,email,phone,body.get("enabled").booleanValue(),body.get("user_session_active").booleanValue(),body.get("client_session_active").booleanValue(),observed,valid);
    }
    private static Instant time(JsonNode body,String field){String value=text(body,field,20);
        if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"))throw unavailable();
        Instant parsed=Instant.parse(value);if(!parsed.toString().equals(value))throw unavailable();return parsed;
    }
    private static String nullable(JsonNode body,String field,int maximum){var value=body.get(field);if(value==null)throw unavailable();return value.isNull()?null:text(body,field,maximum);}
    private static String text(JsonNode body,String field,int maximum){var value=body.get(field);if(value==null||!value.isTextual()||!bounded(value.textValue(),maximum))throw unavailable();return value.textValue();}
    private static boolean bounded(String value,int maximum){return value!=null&&!value.isBlank()&&value.length()<=maximum
        &&value.codePoints().noneMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.isISOControl(c)||(c>=0xd800&&c<=0xdfff));}
    private static long remaining(long started){long left=BUDGET-(System.nanoTime()-started);if(left<=0||Thread.currentThread().isInterrupted())throw unavailable();return left;}
    private static LoginProof.Failure unavailable(){return CodeConfirmation.unavailable();}
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]>{
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final byte[] bytes=new byte[LIMIT];private int size;private java.util.concurrent.Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public synchronized void onSubscribe(java.util.concurrent.Flow.Subscription value){if(subscription!=null||result.isDone()){value.cancel();return;}subscription=value;value.request(1);}
        public synchronized void onNext(List<ByteBuffer> buffers){if(result.isDone())return;for(var buffer:buffers){int count=buffer.remaining();if(count>LIMIT-size){cancel();return;}buffer.get(bytes,size,count);size+=count;}subscription.request(1);}
        public synchronized void onError(Throwable failed){cancel();}
        public synchronized void onComplete(){result.complete(Arrays.copyOf(bytes,size));Arrays.fill(bytes,(byte)0);}
        synchronized void cancel(){if(subscription!=null)subscription.cancel();result.completeExceptionally(unavailable());Arrays.fill(bytes,(byte)0);}
    }
    @Override public void close(){closed=true;for(var exchange:exchanges)exchange.cancel();client.shutdownNow();}
    @Override public String toString(){return "HttpCodeSessionObservations[redacted]";}
}
