package com.arman.bank.authservice.infrastructure;

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

/** Unwired managed-TARGET producer adapter, not legacy-account admission or SQL authority.
 * The store owns the committed callback claim, original verifier custody and private one-shot sink.
 * No retry, caller URL/verifier, refresh grant, mapping, receipt, reservation or session mutation.
 */
public final class LegacyMigrationManagedProviderClient implements MigrationManagedProofPort,AutoCloseable {
    private static final java.util.logging.Logger LOG=java.util.logging.Logger.getLogger(LegacyMigrationManagedProviderClient.class.getName());
    private enum FailurePhase { VALIDATE_ATTEMPT, TOKEN_REQUEST, JWT_VERIFY, OBSERVATION, FACTS_VALIDATE, SINK_VALIDATE, POST_SINK }
    private static void reportFailure(FailurePhase phase){
        try{LOG.warning("ARMONEY_MANAGED_PROVIDER_FAILURE_"+phase.name());}catch(RuntimeException ignored){/* Preserve the existing rejection if logging fails. */}
    }
    public record Settings(URI issuer,URI tokenEndpoint,URI jwks,String clientId,URI redirect){
        public Settings{
            new LegacyMigrationOidcClient.Settings(issuer,jwks,clientId);https(tokenEndpoint);https(redirect);
            if(!Objects.equals(issuer.getScheme(),tokenEndpoint.getScheme())||!Objects.equals(issuer.getRawAuthority(),tokenEndpoint.getRawAuthority()))
                throw new IllegalArgumentException("Invalid managed migration provider settings");
        }
    }
    private final Settings settings;
    private final Clock clock;
    private final HttpClient tokenClient;
    private final LegacyMigrationOidcClient verifier;
    private final MigrationManagedProofPort.Observations observations;
    private final Semaphore slots=new Semaphore(4);
    private final Set<LegacyMigrationOidcExchange> pending=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public LegacyMigrationManagedProviderClient(Settings settings,Clock clock,MigrationManagedProofPort.Observations observations){
        this(settings,clock,observations,owned(settings,clock,observations));
    }
    private record Owned(HttpClient token,LegacyMigrationOidcClient verifier){}
    private LegacyMigrationManagedProviderClient(Settings settings,Clock clock,MigrationManagedProofPort.Observations observations,Owned owned){
        this(settings,clock,observations,owned.token(),owned.verifier());
    }
    private static Owned owned(Settings settings,Clock clock,MigrationManagedProofPort.Observations observations){
        Objects.requireNonNull(settings);Objects.requireNonNull(clock);Objects.requireNonNull(observations);
        HttpClient token=http();
        try{return new Owned(token,new LegacyMigrationOidcClient(new LegacyMigrationOidcClient.Settings(settings.issuer(),settings.jwks(),settings.clientId()),clock));}
        catch(RuntimeException|Error failure){token.shutdownNow();throw failure;}
    }
    /** Controlled synthetic transport seam; all externally constructed settings still require HTTPS. */
    LegacyMigrationManagedProviderClient(Settings settings,Clock clock,MigrationManagedProofPort.Observations observations,
                                        HttpClient tokenClient,LegacyMigrationOidcClient verifier){
        this.settings=Objects.requireNonNull(settings);this.clock=Objects.requireNonNull(clock);this.observations=Objects.requireNonNull(observations);
        this.tokenClient=Objects.requireNonNull(tokenClient);this.verifier=Objects.requireNonNull(verifier);
        if(tokenClient.followRedirects()!=HttpClient.Redirect.NEVER||!tokenClient.connectTimeout().orElse(Duration.ZERO).equals(Duration.ofSeconds(2)))
            throw new IllegalArgumentException("Invalid managed migration provider transport");
    }
    private static HttpClient http(){return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER)
        .proxy(new ProxySelector(){public List<Proxy> select(URI ignored){return List.of(Proxy.NO_PROXY);}
            public void connectFailed(URI ignored,SocketAddress address,java.io.IOException error){}}).build();}
    private static void https(URI uri){if(uri==null||!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null
        ||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getRawPath().isEmpty()||uri.getRawPath().contains("%"))
        throw new IllegalArgumentException("Invalid managed migration provider settings");}
    @Override public void exchange(Attempt attempt,Callback callback,VerifiedSink sink){
        long started=System.nanoTime();
        if(closed||Thread.currentThread().isInterrupted()||!slots.tryAcquire())throw unavailable();
        FailurePhase phase=FailurePhase.VALIDATE_ATTEMPT;
        try{
            if(attempt==null||callback==null||sink==null||!bounded(callback.providerCode(),4096)
                ||!bounded(callback.returnedState(),512)||!bounded(callback.initiatingBrowserCookie(),512))throw unavailable();
            attempt.requireLive();attempt.requireCallback(callback); // Exact ORIGINAL committed code/state/browser claim.
            configured(attempt);remaining(started,attempt);
            String original=attempt.originalVerifier(); // Server decrypts original AEAD custody once; no external verifier field.
            if(original==null||!original.matches("[A-Za-z0-9._~-]{43,128}"))throw unavailable();
            String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.US_ASCII)));
            if(attempt.pkceChallenge()==null||!MessageDigest.isEqual(challenge.getBytes(StandardCharsets.US_ASCII),attempt.pkceChallenge().getBytes(StandardCharsets.US_ASCII)))throw unavailable();
            remaining(started,attempt);attempt.requireLive();remaining(started,attempt);
            phase=FailurePhase.TOKEN_REQUEST;
            String idToken=token(attempt,callback.providerCode(),original,started);
            phase=FailurePhase.JWT_VERIFY;
            var verified=verifier.verifyManaged(idToken,attempt,started); // Explicit MANAGED_TARGET gate, no existing-mapping true callback.
            remaining(started,attempt);attempt.requireLive();long left=remaining(started,attempt);
            var binding=new LoginProof.Binding(verified.issuer(),verified.subject(),verified.clientId(),verified.sessionId());
            phase=FailurePhase.OBSERVATION;
            var facts=observations.observe(binding,attempt.originalDatabaseStart(),Duration.ofNanos(left));
            phase=FailurePhase.FACTS_VALIDATE;
            Instant received=clock.instant();
            Instant evidence=CodeConfirmation.deadline(binding,facts,attempt.originalDatabaseStart(),received);
            if(!facts.enabled()||!facts.userSessionActive()||!facts.clientSessionActive()||facts.email()==null||facts.phone()==null)throw unavailable();
            attempt.requireLive();remaining(started,attempt);Instant finalNow=clock.instant();
            CodeConfirmation.fresh(attempt.originalDatabaseStart(),evidence,finalNow);
            if(!finalNow.isBefore(verified.tokenExpiresAt())||finalNow.getEpochSecond()-verified.authenticatedAt().getEpochSecond()>120)throw unavailable();
            // Only the private creator sink may create a request/thread/backend-bound capability.
            phase=FailurePhase.SINK_VALIDATE;
            sink.accept(new Claims(verified.issuer(),verified.subject(),verified.clientId(),verified.sessionId(),verified.authenticatedAt(),verified.issuedAt(),verified.tokenExpiresAt()),facts);
            phase=FailurePhase.POST_SINK;
            remaining(started,attempt);CodeConfirmation.fresh(attempt.originalDatabaseStart(),evidence,clock.instant());
        }catch(InterruptedException interrupted){reportFailure(phase);Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception rejected){reportFailure(phase);throw unavailable();}
        finally{slots.release();}
    }
    private void configured(Attempt a){
        if(!settings.issuer().toString().equals(a.issuer())||!settings.clientId().equals(a.clientId())||!settings.redirect().toString().equals(a.redirectUri())
            ||!id(a.attemptId())||!id(a.receiptId())||!id(a.migrationId())||!id(a.ownerId())||a.accountProofMode()==null
            ||a.challengeStartedAt()==null||a.originalDatabaseStart()==null||a.originalAccountDeadline()==null||a.originalAttemptDeadline()==null
            ||a.challengeStartedAt().isAfter(clock.instant())||a.originalDatabaseStart().isBefore(a.challengeStartedAt())
            ||a.originalAccountDeadline().isAfter(a.challengeStartedAt().plusSeconds(120))
            ||a.originalAttemptDeadline().isAfter(a.challengeStartedAt().plusSeconds(120)))throw unavailable();
        switch(a.accountProofMode()){
            case LOCAL_PASSWORD->{if(a.expectedSubject()!=null||a.legacyIssuer()!=null||a.legacySubject()!=null)throw unavailable();}
            case LEGACY_OIDC_REAUTH->{if(!a.issuer().equals(a.legacyIssuer())||!bounded(a.legacySubject(),255)||!a.legacySubject().equals(a.expectedSubject()))throw unavailable();}
        }
    }
    private String token(Attempt attempt,String code,String original,long started)throws Exception{
        String encoded="grant_type=authorization_code&client_id="+encode(settings.clientId())+"&redirect_uri="+encode(settings.redirect().toString())
            +"&code="+encode(code)+"&code_verifier="+encode(original);
        byte[] request=encoded.getBytes(StandardCharsets.US_ASCII);if(request.length>8192)throw unavailable();
        var body=new LegacyMigrationOidcExchange.Body();LegacyMigrationOidcExchange exchange=null;
        try{
            var outgoing=HttpRequest.newBuilder(settings.tokenEndpoint()).timeout(Duration.ofNanos(remaining(started,attempt)))
                .header("Content-Type","application/x-www-form-urlencoded").header("Accept","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(request)).build();
            var future=tokenClient.sendAsync(outgoing,info->body);exchange=new LegacyMigrationOidcExchange(future,body);pending.add(exchange);if(closed)exchange.cancel();
            var response=future.get(remaining(started,attempt),TimeUnit.NANOSECONDS);
            var types=response.headers().allValues("Content-Type");
            if(response.statusCode()!=200||types.size()!=1||!types.getFirst().matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?"))throw unavailable();
            var value=LegacyMigrationOidcJson.tokenResponse(response.body());
            LegacyMigrationOidcJson.fields(value,Set.of("access_token","id_token","refresh_token","token_type","expires_in","refresh_expires_in","scope","session_state","not-before-policy"));
            if(!"Bearer".equalsIgnoreCase(LegacyMigrationOidcJson.text(value,"token_type",16)))throw unavailable();
            LegacyMigrationOidcJson.text(value,"access_token",16384); // Discarded; not a banking credential or fallback proof.
            String jwt=LegacyMigrationOidcJson.text(value,"id_token",16384);remaining(started,attempt);return jwt;
        }finally{Arrays.fill(request,(byte)0);body.cancel();if(exchange!=null){exchange.cancel();pending.remove(exchange);}}
    }
    private long remaining(long started,Attempt attempt){
        long left=TimeUnit.SECONDS.toNanos(4)-(System.nanoTime()-started);Instant now=clock.instant();
        if(closed||Thread.currentThread().isInterrupted()||left<=0||attempt.originalDatabaseStart()==null
            ||attempt.originalAccountDeadline()==null||attempt.originalAttemptDeadline()==null||now.isBefore(attempt.originalDatabaseStart())
            ||Duration.between(attempt.originalDatabaseStart(),now).compareTo(Duration.ofSeconds(4))>0)throw unavailable();
        long account=Duration.between(now,attempt.originalAccountDeadline()).toNanos(),original=Duration.between(now,attempt.originalAttemptDeadline()).toNanos();
        if(account<=0||original<=0)throw unavailable();return Math.min(left,Math.min(account,original));
    }
    private static boolean bounded(String value,int maximum){return value!=null&&!value.isEmpty()&&value.length()<=maximum&&value.chars().allMatch(c->c>=33&&c<=126);}
    private static boolean id(UUID value){return value!=null&&(value.getMostSignificantBits()!=0||value.getLeastSignificantBits()!=0);}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static LegacyMigrationOidcClient.Unavailable unavailable(){return new LegacyMigrationOidcClient.Unavailable();}
    @Override public void close(){closed=true;pending.forEach(LegacyMigrationOidcExchange::cancel);tokenClient.shutdownNow();verifier.close();}
    @Override public String toString(){return "LegacyMigrationManagedProviderClient[redacted]";}
}
