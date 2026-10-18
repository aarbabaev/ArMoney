package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.IdentityVerificationStore.Observations;
import com.arman.bank.authservice.domain.IdentityVerification;
import com.arman.bank.authservice.domain.IdentityVerification.*;
import com.arman.bank.authservice.domain.IdentityVerification.Flow;
import com.arman.bank.authservice.domain.Verification;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Owned fixed-origin pre-token observation client. No retries, authority inference or body logging. */
public final class HttpVerificationObservations implements Observations, AutoCloseable {
    private static final String PATH="/sso/realms/armoney/armoney-status/enrollment";
    private static final int LIMIT=12288;
    private static final Set<String> FIELDS=Set.of("issuer","subject","client_id","provider_root_session_id","provider_tab_id","authentication_session_id",
        "username","email","phone_number","enabled","authentication_session_active","authentication_session_expires_at");
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    enum Diagnostic { INITIAL_VALIDATION, CLOSED, START_INVALID, REQUEST_ENCODING, REQUEST_BUILD,
        TRANSPORT, INTERRUPTED, TIMEOUT, CANCELLED, BODY_LIMIT, BODY_FAILURE,
        HTTP_200, HTTP_400, HTTP_401, HTTP_403, HTTP_404, HTTP_503, HTTP_OTHER,
        CONTENT_TYPE, UTF8, JSON_SCHEMA, ECHO, FLAGS, LIFETIME, CONTACT_FIELDS, CONTACTS, ACCEPTED }
    private static final System.Logger LOG=System.getLogger(HttpVerificationObservations.class.getName());
    private final java.util.function.Consumer<Diagnostic> diagnostics;
    private final URI endpoint;
    private final String credential;
    private final Policy policy;
    private final HttpClient client;
    private final Set<CompletableFuture<?>> exchanges=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public HttpVerificationObservations(URI origin,String credential,Policy policy){
        origin(origin,false);credential(credential);this.endpoint=origin.resolve(PATH);this.credential=credential;this.policy=Objects.requireNonNull(policy);
        diagnostics=HttpVerificationObservations::log;
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    /** Isolated loopback fixture only; never selected by environment configuration. Owns the supplied client. */
    HttpVerificationObservations(URI origin,String credential,Policy policy,HttpClient client){
        this(origin,credential,policy,client,HttpVerificationObservations::log);
    }
    /** Fixed enum-only sink seam; cannot observe request/response objects or exception data. */
    HttpVerificationObservations(URI origin,String credential,Policy policy,HttpClient client,java.util.function.Consumer<Diagnostic> diagnostics){
        this.diagnostics=Objects.requireNonNull(diagnostics);
        origin(origin,true);credential(credential);this.endpoint=origin.resolve(PATH);this.credential=credential;this.policy=Objects.requireNonNull(policy);this.client=Objects.requireNonNull(client);
        if(client.followRedirects()!=HttpClient.Redirect.NEVER||!client.connectTimeout().orElse(Duration.ZERO).equals(Duration.ofSeconds(2)))throw new IllegalArgumentException("Invalid observation fixture client");
    }
    static void origin(URI value,boolean fixture){
        if(value==null||value.getHost()==null||value.getUserInfo()!=null||value.getRawQuery()!=null||value.getRawFragment()!=null
                ||!(value.getRawPath().isEmpty()||value.getRawPath().equals("/"))||value.getPort()==0||value.getPort()>65535)invalid();
        boolean https="https".equals(value.getScheme());
        boolean development="http".equals(value.getScheme())&&"sso-service".equals(value.getHost())&&value.getPort()==8080;
        boolean loopback=fixture&&"http".equals(value.getScheme())&&"127.0.0.1".equals(value.getHost())&&value.getPort()>0;
        if(!https&&!development&&!loopback)invalid();
    }
    static void credential(String value){if(value==null||value.length()<32||value.length()>512||value.chars().anyMatch(c->c<33||c>126))invalid();}
    private static void invalid(){throw new IllegalArgumentException("Invalid verification observation configuration");}
    @Override public Observation observe(Flow flow,Instant startedAt){
        return exchange(flow,startedAt,false);
    }
    @Override public Observation observeStatus(Flow flow,Instant startedAt){
        return exchange(flow,startedAt,true);
    }
    private Observation exchange(Flow flow,Instant startedAt,boolean status){
        try{IdentityVerification.validate(flow,policy);}catch(RuntimeException rejected){diagnostic(Diagnostic.INITIAL_VALIDATION);throw rejected;}
        if(closed||startedAt==null){diagnostic(closed?Diagnostic.CLOSED:Diagnostic.START_INVALID);throw unavailable();}
        byte[] requestBytes=null,responseBytes=null;CompletableFuture<HttpResponse<byte[]>> pending=null;var body=new BoundedBody();
        Diagnostic phase=Diagnostic.REQUEST_ENCODING;
        try{
            var fields=new LinkedHashMap<String,Object>();fields.put("issuer",flow.issuer());fields.put("subject",flow.subject());fields.put("client_id",flow.clientId());
            fields.put("provider_root_session_id",flow.rootSessionId());fields.put("provider_tab_id",flow.tabId());fields.put("authentication_session_id",flow.authenticationSessionId());
            requestBytes=JSON.writeValueAsBytes(fields);if(requestBytes.length>LIMIT)throw unavailable();
            phase=Diagnostic.REQUEST_BUILD;
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(4)).header("Content-Type","application/json")
                .header("X-ArMoney-Auth-Status-Key",credential).POST(HttpRequest.BodyPublishers.ofByteArray(requestBytes)).build();
            phase=Diagnostic.TRANSPORT;
            pending=client.sendAsync(request,info->{diagnostic(httpStatus(info.statusCode()));return body;});exchanges.add(pending);if(closed)pending.cancel(true);
            var response=pending.get(4,TimeUnit.SECONDS);responseBytes=response.body();
            var types=response.headers().allValues("Content-Type");
            if(response.statusCode()!=200){phase=null;throw unavailable();}
            phase=Diagnostic.CONTENT_TYPE;
            if(types.size()!=1||!types.getFirst().matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?"))throw unavailable();
            phase=null; // Decoder reports its own finite validation phase; never relabel as transport.
            return decode(flow,startedAt,responseBytes,status);
        }catch(InterruptedException interrupted){diagnostic(Diagnostic.INTERRUPTED);Thread.currentThread().interrupt();throw unavailable();}
        catch(TimeoutException timeout){diagnostic(Diagnostic.TIMEOUT);throw unavailable();}
        catch(CancellationException cancelled){diagnostic(Diagnostic.CANCELLED);throw unavailable();}
        catch(ExecutionException rejected){var kind=transportFailure(rejected);diagnostic(kind!=Diagnostic.TRANSPORT?kind:body.failure!=null?body.failure:kind);throw unavailable();}
        catch(Exception rejected){if(phase!=null)diagnostic(phase==Diagnostic.TRANSPORT?transportFailure(rejected):phase);throw unavailable();}
        finally{
            body.cancel();if(pending!=null){exchanges.remove(pending);if(!pending.isDone())pending.cancel(true);}
            if(requestBytes!=null)Arrays.fill(requestBytes,(byte)0);if(responseBytes!=null)Arrays.fill(responseBytes,(byte)0);
        }
    }
    private Observation decode(Flow expected,Instant startedAt,byte[] bytes,boolean status)throws Exception{
        Diagnostic phase=Diagnostic.BODY_LIMIT;
        try{
            if(bytes==null||bytes.length>LIMIT)throw unavailable();
            phase=Diagnostic.UTF8;
            String source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            phase=Diagnostic.JSON_SCHEMA;
            var body=JSON.readTree(source);if(body==null||!body.isObject()||body.size()!=FIELDS.size())throw unavailable();
            var names=body.fieldNames();while(names.hasNext())if(!FIELDS.contains(names.next()))throw unavailable();
            phase=Diagnostic.ECHO;
            var echoed=new Flow(text(body,"issuer",512),text(body,"subject",255),text(body,"client_id",128),text(body,"provider_root_session_id",255),text(body,"provider_tab_id",255),text(body,"authentication_session_id",43));
            IdentityVerification.validate(echoed,policy);
            if(!expected.issuer().equals(echoed.issuer())||!expected.subject().equals(echoed.subject())||!expected.clientId().equals(echoed.clientId())
                    ||!expected.rootSessionId().equals(echoed.rootSessionId())||!expected.tabId().equals(echoed.tabId())
                    ||!MessageDigest.isEqual(Base64.getUrlDecoder().decode(expected.authenticationSessionId()),Base64.getUrlDecoder().decode(echoed.authenticationSessionId())))throw unavailable();
            phase=Diagnostic.FLAGS;
            for(String field:List.of("enabled","authentication_session_active"))if(!body.path(field).isBoolean()||(!status&&!body.path(field).booleanValue()))throw unavailable();
            phase=Diagnostic.LIFETIME;
            String timestamp=text(body,"authentication_session_expires_at",40);Instant expiry=Instant.parse(timestamp);
            if(!expiry.toString().equals(timestamp)||!timestamp.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T.*Z"))throw unavailable();
            phase=Diagnostic.CONTACT_FIELDS;
            var result=new Observation(expected,text(body,"username",13),text(body,"email",254),text(body,"phone_number",13),body.path("enabled").booleanValue(),body.path("authentication_session_active").booleanValue(),expiry,startedAt);
            // Domain validation retains its exact order. BAD_INPUT identifies contacts only here;
            // lifetime/current-anchor failures retain UNAVAILABLE and are never called transport errors.
            phase=Diagnostic.LIFETIME;
            try{if(status)IdentityVerification.statusEvidence(expected,result,startedAt);else IdentityVerification.evidence(expected,result,startedAt);}
            catch(Verification.Failure rejected){if(rejected.error()==Verification.Error.BAD_INPUT)phase=Diagnostic.CONTACTS;throw rejected;}
            diagnostic(Diagnostic.ACCEPTED);return result;
        }catch(Exception rejected){diagnostic(phase);throw rejected;}
    }
    private static Diagnostic httpStatus(int status){return switch(status){
        case 200->Diagnostic.HTTP_200;case 400->Diagnostic.HTTP_400;case 401->Diagnostic.HTTP_401;
        case 403->Diagnostic.HTTP_403;case 404->Diagnostic.HTTP_404;case 503->Diagnostic.HTTP_503;default->Diagnostic.HTTP_OTHER;};}
    private static Diagnostic transportFailure(Throwable failure){
        // Class tests only: never render or retain Throwable messages, causes, URLs or headers.
        for(int depth=0;failure!=null&&depth<8;depth++,failure=failure.getCause()){
            if(failure instanceof HttpTimeoutException||failure instanceof TimeoutException)return Diagnostic.TIMEOUT;
            if(failure instanceof CancellationException)return Diagnostic.CANCELLED;
        }
        return Diagnostic.TRANSPORT;
    }
    static String diagnosticLine(Diagnostic value){return "AUTH_ENROLLMENT_OBSERVER "+value.name();}
    private static void log(Diagnostic value){LOG.log(System.Logger.Level.INFO,diagnosticLine(value));}
    private void diagnostic(Diagnostic value){try{diagnostics.accept(value);}catch(RuntimeException ignored){/* Logging cannot alter admission. */}}
    private static String text(JsonNode body,String field,int max){var value=body.get(field);if(value==null||!value.isTextual()||value.textValue().isBlank()||value.textValue().length()>max)throw unavailable();return value.textValue();}
    private static Verification.Failure unavailable(){return new Verification.Failure(Verification.Error.UNAVAILABLE);}
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]>{
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final byte[] bytes=new byte[LIMIT];private int size;private java.util.concurrent.Flow.Subscription subscription;private volatile Diagnostic failure;
        public CompletionStage<byte[]> getBody(){return result;}
        public synchronized void onSubscribe(java.util.concurrent.Flow.Subscription value){if(subscription!=null||result.isDone()){value.cancel();return;}subscription=value;value.request(1);}
        public synchronized void onNext(List<ByteBuffer> buffers){if(result.isDone())return;for(var buffer:buffers){int count=buffer.remaining();if(count>LIMIT-size){failure=Diagnostic.BODY_LIMIT;cancel();return;}buffer.get(bytes,size,count);size+=count;}subscription.request(1);}
        public synchronized void onError(Throwable ignored){if(failure==null){var kind=transportFailure(ignored);failure=kind==Diagnostic.TRANSPORT?Diagnostic.BODY_FAILURE:kind;}cancel();}
        public synchronized void onComplete(){result.complete(Arrays.copyOf(bytes,size));Arrays.fill(bytes,(byte)0);}
        synchronized void cancel(){if(subscription!=null)subscription.cancel();result.completeExceptionally(unavailable());Arrays.fill(bytes,(byte)0);}
    }
    @Override public void close(){closed=true;for(var exchange:exchanges)exchange.cancel(true);client.shutdownNow();}
    @Override public String toString(){return "HttpVerificationObservations[redacted]";}
}
