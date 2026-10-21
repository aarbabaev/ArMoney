package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.MigrationLegacyAccountProofPort;
import com.arman.bank.authservice.application.MigrationManagedProofPort;
import com.arman.bank.authservice.domain.CodeConfirmation;
import com.arman.bank.authservice.domain.LoginProof;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Unwired LEGACY_ACCOUNT exchange. Owner/subject are unknown until authentic token and session facts.
 * Only the creator may subsequently resolve an exact EXISTING mapping on PRIMARY; this adapter
 * has no store, mapping gate, contact lookup, identity allocation or admission-authority factory.
 */
public final class LegacyMigrationLegacyAccountProviderClient implements MigrationLegacyAccountProofPort,AutoCloseable {
    public record Settings(URI issuer,URI tokenEndpoint,URI jwks,String clientId,URI redirect){
        public Settings{
            new LegacyMigrationOidcClient.Settings(issuer,jwks,clientId);https(tokenEndpoint);https(redirect);
            if(!Objects.equals(issuer.getRawAuthority(),tokenEndpoint.getRawAuthority()))throw new IllegalArgumentException("Invalid legacy account provider settings");
        }
    }
    private final Settings settings;private final Clock clock;private final HttpClient client;
    private final LegacyMigrationOidcClient verifier;private final MigrationManagedProofPort.Observations observations;
    private final Semaphore slots=new Semaphore(4);private final Set<LegacyMigrationOidcExchange> pending=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private record Owned(HttpClient client,LegacyMigrationOidcClient verifier){}
    public LegacyMigrationLegacyAccountProviderClient(Settings settings,Clock clock,MigrationManagedProofPort.Observations observations){
        this(settings,clock,observations,owned(settings,clock,observations));
    }
    private LegacyMigrationLegacyAccountProviderClient(Settings settings,Clock clock,MigrationManagedProofPort.Observations observations,Owned owned){
        this(settings,clock,observations,owned.client(),owned.verifier());
    }
    private static Owned owned(Settings settings,Clock clock,MigrationManagedProofPort.Observations observations){
        Objects.requireNonNull(settings);Objects.requireNonNull(clock);Objects.requireNonNull(observations);
        HttpClient client=http();
        try{return new Owned(client,new LegacyMigrationOidcClient(new LegacyMigrationOidcClient.Settings(settings.issuer(),settings.jwks(),settings.clientId()),clock));}
        catch(RuntimeException|Error failure){client.shutdownNow();throw failure;}
    }
    /** Controlled synthetic transport seam; no environment-selected trust or URL overrides. */
    LegacyMigrationLegacyAccountProviderClient(Settings settings,Clock clock,MigrationManagedProofPort.Observations observations,
                                              HttpClient client,LegacyMigrationOidcClient verifier){
        this.settings=Objects.requireNonNull(settings);this.clock=Objects.requireNonNull(clock);this.observations=Objects.requireNonNull(observations);
        this.client=Objects.requireNonNull(client);this.verifier=Objects.requireNonNull(verifier);
        if(client.followRedirects()!=HttpClient.Redirect.NEVER||!client.connectTimeout().orElse(Duration.ZERO).equals(Duration.ofSeconds(2)))
            throw new IllegalArgumentException("Invalid legacy account provider transport");
    }
    private static HttpClient http(){return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER)
        .proxy(new ProxySelector(){public List<Proxy> select(URI ignored){return List.of(Proxy.NO_PROXY);}
            public void connectFailed(URI ignored,SocketAddress address,java.io.IOException error){}}).build();}
    private static void https(URI uri){if(uri==null||!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null
        ||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getRawPath().isEmpty()||uri.getRawPath().contains("%"))
        throw new IllegalArgumentException("Invalid legacy account provider settings");}
    @Override public void exchange(Attempt attempt,MigrationManagedProofPort.Callback callback,VerifiedSink sink){
        long started=System.nanoTime();
        if(closed||Thread.currentThread().isInterrupted()||!slots.tryAcquire())throw unavailable();
        try{
            if(attempt==null||callback==null||sink==null||!bounded(callback.providerCode(),4096)||!bounded(callback.returnedState(),512)
                ||!bounded(callback.initiatingBrowserCookie(),512))throw unavailable();
            attempt.requireLive();attempt.requireCallback(callback);configured(attempt);remaining(started,attempt);
            String original=attempt.originalVerifier();
            if(original==null||!original.matches("[A-Za-z0-9._~-]{43,128}"))throw unavailable();
            String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.US_ASCII)));
            if(attempt.pkceChallenge()==null||!MessageDigest.isEqual(challenge.getBytes(StandardCharsets.US_ASCII),attempt.pkceChallenge().getBytes(StandardCharsets.US_ASCII)))throw unavailable();
            remaining(started,attempt);attempt.requireLive();remaining(started,attempt);
            String idToken=token(attempt,callback.providerCode(),original,started);
            var verified=verifier.verifyAccount(idToken,attempt,started); // Dedicated unknown-subject ACCOUNT gate, never existing mapping=true.
            remaining(started,attempt);attempt.requireLive();long left=remaining(started,attempt);
            var binding=new LoginProof.Binding(verified.issuer(),verified.subject(),verified.clientId(),verified.sessionId());
            var facts=observations.observe(binding,attempt.originalDatabaseStart(),Duration.ofNanos(left));
            Instant evidence=CodeConfirmation.deadline(binding,facts,attempt.originalDatabaseStart(),clock.instant());
            if(!facts.enabled()||!facts.userSessionActive()||!facts.clientSessionActive()||facts.email()==null||facts.phone()==null)throw unavailable();
            attempt.requireLive();remaining(started,attempt);Instant finalNow=clock.instant();
            CodeConfirmation.fresh(attempt.originalDatabaseStart(),evidence,finalNow);
            if(!finalNow.isBefore(verified.tokenExpiresAt())||finalNow.getEpochSecond()-verified.authenticatedAt().getEpochSecond()>120)throw unavailable();
            sink.accept(new MigrationManagedProofPort.Claims(verified.issuer(),verified.subject(),verified.clientId(),verified.sessionId(),
                verified.authenticatedAt(),verified.issuedAt(),verified.tokenExpiresAt()),facts);
            remaining(started,attempt);CodeConfirmation.fresh(attempt.originalDatabaseStart(),evidence,clock.instant());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception rejected){throw unavailable();}
        finally{slots.release();}
    }
    private void configured(Attempt a){
        if(a.attemptId()==null||(a.attemptId().getMostSignificantBits()==0&&a.attemptId().getLeastSignificantBits()==0)
            ||!settings.issuer().toString().equals(a.issuer())||!settings.clientId().equals(a.clientId())||!settings.redirect().toString().equals(a.redirectUri())
            ||a.challengeStartedAt()==null||a.originalDatabaseStart()==null||a.originalAttemptDeadline()==null||a.challengeStartedAt().isAfter(clock.instant())
            ||a.originalDatabaseStart().isBefore(a.challengeStartedAt())||a.originalAttemptDeadline().isAfter(a.challengeStartedAt().plusSeconds(120)))throw unavailable();
    }
    private String token(Attempt attempt,String code,String original,long started)throws Exception{
        String form="grant_type=authorization_code&client_id="+encode(settings.clientId())+"&redirect_uri="+encode(settings.redirect().toString())
            +"&code="+encode(code)+"&code_verifier="+encode(original);
        byte[] request=form.getBytes(StandardCharsets.US_ASCII);if(request.length>8192)throw unavailable();
        var body=new LegacyMigrationOidcExchange.Body();LegacyMigrationOidcExchange exchange=null;
        try{
            var outgoing=HttpRequest.newBuilder(settings.tokenEndpoint()).timeout(Duration.ofNanos(remaining(started,attempt)))
                .header("Content-Type","application/x-www-form-urlencoded").header("Accept","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(request)).build();
            var future=client.sendAsync(outgoing,info->body);exchange=new LegacyMigrationOidcExchange(future,body);pending.add(exchange);if(closed)exchange.cancel();
            var response=future.get(remaining(started,attempt),TimeUnit.NANOSECONDS);var types=response.headers().allValues("Content-Type");
            if(response.statusCode()!=200||types.size()!=1||!types.getFirst().matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?"))throw unavailable();
            var value=LegacyMigrationOidcJson.tokenResponse(response.body());
            LegacyMigrationOidcJson.fields(value,Set.of("access_token","id_token","refresh_token","token_type","expires_in","refresh_expires_in","scope","session_state","not-before-policy"));
            if(!"Bearer".equalsIgnoreCase(LegacyMigrationOidcJson.text(value,"token_type",16)))throw unavailable();
            LegacyMigrationOidcJson.text(value,"access_token",16384);
            String jwt=LegacyMigrationOidcJson.text(value,"id_token",16384);remaining(started,attempt);return jwt;
        }finally{Arrays.fill(request,(byte)0);body.cancel();if(exchange!=null){exchange.cancel();pending.remove(exchange);}}
    }
    private long remaining(long started,Attempt a){
        long left=TimeUnit.SECONDS.toNanos(4)-(System.nanoTime()-started);Instant now=clock.instant();
        if(closed||Thread.currentThread().isInterrupted()||left<=0||a.originalDatabaseStart()==null||a.originalAttemptDeadline()==null
            ||now.isBefore(a.originalDatabaseStart())||Duration.between(a.originalDatabaseStart(),now).compareTo(Duration.ofSeconds(4))>0)throw unavailable();
        long original=Duration.between(now,a.originalAttemptDeadline()).toNanos();if(original<=0)throw unavailable();return Math.min(left,original);
    }
    private static boolean bounded(String value,int max){return value!=null&&!value.isEmpty()&&value.length()<=max&&value.chars().allMatch(c->c>=33&&c<=126);}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static LegacyMigrationOidcClient.Unavailable unavailable(){return new LegacyMigrationOidcClient.Unavailable();}
    @Override public void close(){closed=true;pending.forEach(LegacyMigrationOidcExchange::cancel);client.shutdownNow();verifier.close();}
    @Override public String toString(){return "LegacyMigrationLegacyAccountProviderClient[redacted]";}
}
