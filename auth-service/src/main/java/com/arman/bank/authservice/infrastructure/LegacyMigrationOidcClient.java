package com.arman.bank.authservice.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.net.*;
import java.net.http.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** Unwired signature/claim adapter. Validated data is NOT account admission or banking authority.
 * Caller must supply an existing-mapping, browser/state/PKCE/single-use admission gate and
 * separately validate the exact twelve-field session observation before creating a receipt.
 * Does not exchange codes, create receipts/capabilities, link owners or renew any proof.
 */
public final class LegacyMigrationOidcClient implements AutoCloseable {
    public static final class Unavailable extends RuntimeException {
        public Unavailable(){super("Migration proof unavailable",null,false,false);}
    }
    public record Settings(URI issuer,URI jwks,String clientId) {
        public Settings {
            endpoint(issuer);endpoint(jwks);
            if(clientId==null||!clientId.matches("[A-Za-z0-9._-]{1,128}"))throw new IllegalArgumentException("Invalid migration verifier settings");
        }
    }
    public record Attempt(UUID existingOwner,String issuer,String subject,String sessionId,String nonce,
                          Instant challengeStartedAt,Instant originalDatabaseStart,Instant originalDeadline) {
        @Override public String toString(){return "MigrationOidcAttempt[REDACTED]";}
    }
    @FunctionalInterface public interface AttemptGate {
        /** Must recheck trusted, already-admitted existing mapping, browser and one-use attempt.
         * A true response does not replace the caller's separately bound session observation.
         */
        boolean admitted(Attempt attempt);
    }
    public record ValidatedProof(UUID existingOwner,String issuer,String subject,String clientId,String sessionId,
                                 Instant authenticatedAt,Instant issuedAt,Instant tokenExpiresAt,
                                 Instant originalDatabaseStart,Instant originalDeadline) {
        @Override public String toString(){return "ValidatedMigrationJwt[REDACTED]";}
    }
    private record Cache(Map<String,RSAPublicKey> keys,long expires){}
    private final Settings settings;
    private final Clock clock;
    private final HttpClient client;
    private final LongSupplier nanos;
    private final Semaphore slots=new Semaphore(4);
    private final ReentrantLock fetchLock=new ReentrantLock();
    private final Set<LegacyMigrationOidcExchange> exchanges=ConcurrentHashMap.newKeySet();
    private volatile Cache cache;
    private volatile boolean closed;
    private boolean fetched;
    private long lastFetch;
    public LegacyMigrationOidcClient(Settings settings,Clock clock){this(settings,clock,newClient());}
    /** Package-private owned-client seam for isolated transport tests; still requires HTTPS settings. */
    LegacyMigrationOidcClient(Settings settings,Clock clock,HttpClient client){
        this(settings,clock,client,System::nanoTime);
    }
    LegacyMigrationOidcClient(Settings settings,Clock clock,HttpClient client,LongSupplier nanos){
        this.settings=Objects.requireNonNull(settings);this.clock=Objects.requireNonNull(clock);this.client=Objects.requireNonNull(client);
        this.nanos=Objects.requireNonNull(nanos);
        if(client.followRedirects()!=HttpClient.Redirect.NEVER || !client.connectTimeout().orElse(Duration.ZERO).equals(Duration.ofSeconds(2)))
            throw new IllegalArgumentException("Invalid migration verifier transport");
    }
    private static HttpClient newClient(){
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector(){public List<Proxy> select(URI ignored){return List.of(Proxy.NO_PROXY);}
                public void connectFailed(URI ignored,SocketAddress address,java.io.IOException error){}}).build();
    }
    private static void endpoint(URI uri){
        if(uri==null||!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null
            ||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getRawPath().isEmpty()||uri.getRawPath().contains("%"))
            throw new IllegalArgumentException("Invalid migration verifier settings");
    }
    public ValidatedProof verify(String compact,Attempt attempt,AttemptGate gate){
        if(attempt==null||gate==null||attempt.nonce()==null)throw denied();
        return verifyBound(compact,attempt.existingOwner(),attempt.subject(),attempt.sessionId(),attempt.nonce()::equals,
            attempt.challengeStartedAt(),attempt.originalDatabaseStart(),attempt.originalDeadline(),()->context(attempt,gate),nanos.getAsLong());
    }
    /** Separate managed-target purpose. NEVER constructs an existing-mapping Attempt or a true account gate. */
    ValidatedProof verifyManaged(String compact,com.arman.bank.authservice.application.MigrationManagedProofPort.Attempt attempt,
                                long originalMonotonicStart){
        if(attempt==null)throw denied();
        Instant end=minimum(attempt.originalAccountDeadline(),attempt.originalAttemptDeadline());
        return verifyBound(compact,attempt.ownerId(),attempt.expectedSubject(),null,attempt::nonceMatches,
            attempt.challengeStartedAt(),attempt.originalDatabaseStart(),end,()->managedContext(attempt,end),originalMonotonicStart);
    }
    /** Unknown subject/account purpose. Only its authenticated claims may later select an EXISTING SQL mapping. */
    record AccountTokenData(String issuer,String subject,String clientId,String sessionId,Instant authenticatedAt,
                            Instant issuedAt,Instant tokenExpiresAt){
        @Override public String toString(){return "MigrationAccountTokenData[redacted]";}
    }
    AccountTokenData verifyAccount(String compact,com.arman.bank.authservice.application.MigrationLegacyAccountProofPort.Attempt attempt,
                                   long originalMonotonicStart){
        if(attempt==null)throw denied();
        // No existing-owner or subject is invented: the distinct account gate checks only the admitted attempt.
        var value=verifyBound(compact,null,null,null,attempt::nonceMatches,attempt.challengeStartedAt(),attempt.originalDatabaseStart(),
            attempt.originalAttemptDeadline(),()->accountContext(attempt),originalMonotonicStart);
        return new AccountTokenData(value.issuer(),value.subject(),value.clientId(),value.sessionId(),value.authenticatedAt(),value.issuedAt(),value.tokenExpiresAt());
    }
    private void accountContext(com.arman.bank.authservice.application.MigrationLegacyAccountProofPort.Attempt a){
        Instant now=clock.instant();
        if(closed||a.attemptId()==null||(a.attemptId().getMostSignificantBits()==0&&a.attemptId().getLeastSignificantBits()==0)
            ||!settings.issuer().toString().equals(a.issuer())||!settings.clientId().equals(a.clientId())
            ||a.challengeStartedAt()==null||a.originalDatabaseStart()==null||a.originalAttemptDeadline()==null
            ||a.challengeStartedAt().isAfter(now)||a.originalDatabaseStart().isBefore(a.challengeStartedAt())||a.originalDatabaseStart().isAfter(now)
            ||Duration.between(a.originalDatabaseStart(),now).compareTo(Duration.ofSeconds(4))>0
            ||a.originalAttemptDeadline().isAfter(a.challengeStartedAt().plusSeconds(120))||!now.isBefore(a.originalAttemptDeadline()))throw denied();
        a.requireLive();
    }
    private void managedContext(com.arman.bank.authservice.application.MigrationManagedProofPort.Attempt a,Instant end){
        Instant now=clock.instant();
        if(closed||a.ownerId()==null||a.accountProofMode()==null||!settings.issuer().toString().equals(a.issuer())
            ||!settings.clientId().equals(a.clientId())||a.challengeStartedAt()==null||a.originalDatabaseStart()==null||end==null
            ||a.challengeStartedAt().isAfter(now)||a.originalDatabaseStart().isBefore(a.challengeStartedAt())
            ||a.originalDatabaseStart().isAfter(now)||Duration.between(a.originalDatabaseStart(),now).compareTo(Duration.ofSeconds(4))>0
            ||end.isAfter(a.challengeStartedAt().plusSeconds(120))||!now.isBefore(end))throw denied();
        switch(a.accountProofMode()){
            case LOCAL_PASSWORD->{if(a.legacyIssuer()!=null||a.legacySubject()!=null||a.expectedSubject()!=null)throw denied();}
            case LEGACY_OIDC_REAUTH->{if(!a.issuer().equals(a.legacyIssuer())||!bounded(a.legacySubject(),255)
                ||!a.legacySubject().equals(a.expectedSubject()))throw denied();}
        }
        a.requireLive();
    }
    private static Instant minimum(Instant a,Instant b){if(a==null||b==null)throw denied();return a.isBefore(b)?a:b;}
    private ValidatedProof verifyBound(String compact,UUID owner,String expectedSubject,String expectedSession,
            java.util.function.Predicate<String> nonceMatch,Instant challengeStartedAt,Instant databaseStart,Instant deadline,
            Runnable trustedGate,long started){
        if(closed||Thread.currentThread().isInterrupted()||!slots.tryAcquire())throw denied();
        try {
            trustedGate.run();
            if(compact==null||compact.length()>16384)throw denied();
            String[] parts=compact.split("\\.",-1);if(parts.length!=3)throw denied();
            JsonNode header=LegacyMigrationOidcJson.object(LegacyMigrationOidcJson.segment(parts[0]),12288);
            JsonNode claims=LegacyMigrationOidcJson.object(LegacyMigrationOidcJson.segment(parts[1]),12288);
            LegacyMigrationOidcJson.segment(parts[2]);
            LegacyMigrationOidcJson.fields(header,Set.of("alg","kid","typ"));
            if(!"RS256".equals(text(header,"alg",16)) || header.has("typ")&&!"JWT".equals(text(header,"typ",16)))throw denied();
            String kid=text(header,"kid",128);if(!kid.matches("[!-~]{1,128}"))throw denied();
            // Select only from the trusted configuration's bounded cache, never a JWT URL/key.
            RSAPublicKey key=key(kid,started,deadline);
            if(!SignedJWT.parse(compact).verify(new RSASSAVerifier(key)))throw denied();
            Instant now=clock.instant();long current=now.getEpochSecond();
            if(!settings.issuer().toString().equals(text(claims,"iss",512))||expectedSubject!=null&&!expectedSubject.equals(text(claims,"sub",255))
                ||expectedSession!=null&&!expectedSession.equals(text(claims,"sid",255))||!nonceMatch.test(text(claims,"nonce",256)))throw denied();
            String subject=text(claims,"sub",255),session=text(claims,"sid",255);
            JsonNode audience=claims.get("aud");
            if(audience==null || !(audience.isTextual()&&settings.clientId().equals(audience.textValue())
                ||audience.isArray()&&audience.size()==1&&audience.get(0).isTextual()&&settings.clientId().equals(audience.get(0).textValue())))throw denied();
            if(claims.has("azp")&&!settings.clientId().equals(text(claims,"azp",128)))throw denied();
            long auth=seconds(claims,"auth_time"),iat=seconds(claims,"iat"),exp=seconds(claims,"exp");
            if(auth<challengeStartedAt.getEpochSecond()-5||auth>current+5||current-auth>120
                ||iat<auth||iat>current+5||exp<=current||exp<=iat||claims.has("nbf")&&seconds(claims,"nbf")>current+5)throw denied();
            remaining(started,deadline);trustedGate.run();
            // The trusted gate may block, close or interrupt this invocation. Its pre-call
            // clock sample cannot authorize a late result; do not invoke the gate again.
            remaining(started,deadline);
            Instant finalNow=clock.instant();
            if(finalNow.isBefore(databaseStart)
                ||Duration.between(databaseStart,finalNow).compareTo(Duration.ofSeconds(4))>0
                ||!finalNow.isBefore(deadline)||finalNow.getEpochSecond()>=exp||finalNow.getEpochSecond()-auth>120
                ||closed||Thread.currentThread().isInterrupted())throw denied();
            return new ValidatedProof(owner,settings.issuer().toString(),subject,settings.clientId(),session,
                Instant.ofEpochSecond(auth),Instant.ofEpochSecond(iat),Instant.ofEpochSecond(exp),databaseStart,deadline);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw denied();}
        catch(Exception ignored){throw denied();}
        finally{slots.release();}
    }
    private void context(Attempt a,AttemptGate gate){
        Instant now=clock.instant();
        if(closed||a==null||gate==null||a.existingOwner()==null||a.existingOwner().getMostSignificantBits()==0&&a.existingOwner().getLeastSignificantBits()==0
            ||!settings.issuer().toString().equals(a.issuer())||!bounded(a.subject(),255)||!bounded(a.sessionId(),255)||!bounded(a.nonce(),256)
            ||a.challengeStartedAt()==null||a.originalDatabaseStart()==null||a.originalDeadline()==null
            ||a.challengeStartedAt().isAfter(now)||a.originalDatabaseStart().isBefore(a.challengeStartedAt())||a.originalDatabaseStart().isAfter(now)
            ||Duration.between(a.originalDatabaseStart(),now).compareTo(Duration.ofSeconds(4))>0
            ||a.originalDeadline().isAfter(a.challengeStartedAt().plusSeconds(120))||!now.isBefore(a.originalDeadline())||!gate.admitted(a))throw denied();
    }
    private static boolean bounded(String s,int max){return s!=null&&!s.isEmpty()&&s.length()<=max&&!s.chars().anyMatch(Character::isISOControl);}
    private RSAPublicKey key(String kid,long started,Instant deadline)throws Exception{
        Cache existing=cache;long now=nanos.getAsLong();
        if(existing!=null&&now-existing.expires()<0&&existing.keys().containsKey(kid))return existing.keys().get(kid);
        if(!fetchLock.tryLock(remaining(started,deadline),TimeUnit.NANOSECONDS))throw denied();
        try{
            existing=cache;now=nanos.getAsLong();
            if(existing!=null&&now-existing.expires()<0&&existing.keys().containsKey(kid))return existing.keys().get(kid);
            if(fetched&&now-lastFetch<TimeUnit.SECONDS.toNanos(30))throw denied();
            fetched=true;lastFetch=now;
            byte[] bytes=fetch(started,deadline);Map<String,RSAPublicKey> keys=keys(bytes);
            remaining(started,deadline);if(closed)throw denied();
            cache=new Cache(Map.copyOf(keys),nanos.getAsLong()+TimeUnit.MINUTES.toNanos(5));
            if(!keys.containsKey(kid))throw denied();return keys.get(kid);
        }finally{fetchLock.unlock();}
    }
    private byte[] fetch(long started,Instant deadline)throws Exception{
        var request=HttpRequest.newBuilder(settings.jwks()).timeout(Duration.ofNanos(remaining(started,deadline)))
            .header("Accept","application/json").GET().build();
        var body=new LegacyMigrationOidcExchange.Body();
        var pending=client.sendAsync(request,info->body);var exchange=new LegacyMigrationOidcExchange(pending,body);
        exchanges.add(exchange);if(closed)exchange.cancel();
        try{
            var response=pending.get(remaining(started,deadline),TimeUnit.NANOSECONDS);
            var types=response.headers().allValues("Content-Type");
            if(response.statusCode()!=200||types.size()!=1||!types.getFirst().matches("(?i)application/(?:json|jwk-set\\+json)(?:\\s*;\\s*charset=utf-8)?"))throw denied();
            remaining(started,deadline);return response.body();
        }finally{exchange.cancel();exchanges.remove(exchange);}
    }
    private static Map<String,RSAPublicKey> keys(byte[] raw)throws Exception{
        JsonNode root=LegacyMigrationOidcJson.object(raw,65536);LegacyMigrationOidcJson.fields(root,Set.of("keys"));
        JsonNode list=root.get("keys");if(list==null||!list.isArray()||list.isEmpty()||list.size()>32)throw denied();
        Map<String,RSAPublicKey> result=new HashMap<>();
        for(JsonNode entry:list){
            if(!entry.isObject())throw denied();
            LegacyMigrationOidcJson.fields(entry,Set.of("kty","kid","n","e","alg","use","key_ops","x5c","x5t","x5t#S256"));
            String kid=text(entry,"kid",128);
            if(!kid.matches("[!-~]{1,128}")||!"RSA".equals(text(entry,"kty",16))
                ||entry.has("alg")&&!"RS256".equals(text(entry,"alg",16))||entry.has("use")&&!"sig".equals(text(entry,"use",16)))throw denied();
            if(entry.has("key_ops")){JsonNode ops=entry.get("key_ops");if(!ops.isArray()||ops.size()!=1||!ops.get(0).isTextual()||!"verify".equals(ops.get(0).textValue()))throw denied();}
            // Only metadata from the fixed trusted JWKS; never a source of URLs or verification keys.
            if(entry.has("x5c")){JsonNode certs=entry.get("x5c");if(!certs.isArray()||certs.isEmpty()||certs.size()>4)throw denied();
                for(JsonNode cert:certs){
                    if(!cert.isTextual()||cert.textValue().isEmpty()||cert.textValue().length()>12288)throw denied();
                    byte[] encoded=Base64.getDecoder().decode(cert.textValue());
                    if(!Base64.getEncoder().encodeToString(encoded).equals(cert.textValue()))throw denied();
                }}
            for(String thumb:List.of("x5t","x5t#S256"))if(entry.has(thumb)){
                byte[] digest=LegacyMigrationOidcJson.segment(text(entry,thumb,128));
                if(digest.length!=(thumb.equals("x5t")?20:32))throw denied();
            }
            LegacyMigrationOidcJson.segment(text(entry,"n",2048));LegacyMigrationOidcJson.segment(text(entry,"e",16));
            RSAPublicKey rsa=RSAKey.parse(entry.toString()).toRSAPublicKey();
            if(rsa.getModulus().bitLength()<2048||rsa.getModulus().bitLength()>8192||result.putIfAbsent(kid,rsa)!=null)throw denied();
        }
        return result;
    }
    private long remaining(long started,Instant deadline){
        long duration=TimeUnit.SECONDS.toNanos(4)-(nanos.getAsLong()-started);
        long original=Duration.between(clock.instant(),deadline).toNanos();
        if(closed||Thread.currentThread().isInterrupted()||duration<=0||original<=0)throw denied();return Math.min(duration,original);
    }
    private static String text(JsonNode n,String field,int max){return LegacyMigrationOidcJson.text(n,field,max);}
    private static long seconds(JsonNode n,String field){return LegacyMigrationOidcJson.seconds(n,field);}
    private static Unavailable denied(){return new Unavailable();}
    @Override public void close(){closed=true;exchanges.forEach(LegacyMigrationOidcExchange::cancel);cache=null;client.shutdownNow();}
}
