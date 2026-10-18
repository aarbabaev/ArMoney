package com.arman.bank.authservice.application;

import com.arman.bank.authservice.domain.Identity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import static com.arman.bank.authservice.application.AuthFailure.Kind.*;

public final class AuthService {
    public record Session(String accessToken, Instant expiresAt, boolean boundedLifetime) {
        public Session(String accessToken,Instant expiresAt){this(accessToken,expiresAt,false);}
        @Override public String toString() { return "Session[REDACTED]"; }
    }
    private final AuthStore store;
    private final PasswordHasher passwords;
    private final Clock clock;
    private final RegistrationProfiles profiles;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;
    private final ManagedBankingService managed;
    private final java.util.function.BiPredicate<String,String> legacyPairs;

    public AuthService(AuthStore store, PasswordHasher passwords, Clock clock, RegistrationProfiles profiles) {
        this(store,passwords,clock,profiles,null,null);
    }

    /** Explicit composed routing only; unknown pairs never choose the legacy path. */
    public AuthService(AuthStore store, PasswordHasher passwords, Clock clock, RegistrationProfiles profiles,
                       ManagedBankingService managed, java.util.function.BiPredicate<String,String> legacyPairs) {
        if((managed==null)!=(legacyPairs==null))throw new IllegalArgumentException("Incomplete managed routing");
        this.managed=managed;this.legacyPairs=legacyPairs;
        this.store = store;
        this.passwords = passwords;
        this.clock = clock;
        this.profiles = java.util.Objects.requireNonNull(profiles);
        dummyHash = passwords.hash(UUID.randomUUID().toString());
    }

    public void register(String email, String password, String phone) {
        requireLegacyAdmission();
        email = normalizeEmail(email);
        validatePassword(password);
        if (!canonicalPhone(phone)) throw new AuthFailure(BAD_INPUT);
        limit(email);
        // Always hash; an identical retry never overwrites the original credentials.
        provision(store.register(new Identity(UUID.randomUUID(), email, phone), passwords.hash(password)));
    }

    /** Explicit restricted admission only. Not routed or activated by ordinary login. */
    public com.arman.bank.authservice.domain.LegacyMigration.Admission beginLegacyMigration(
            String email,String password,UUID commandId,LegacyMigrationStore migrations){
        return beginLegacyMigration(email,password,commandId,new LegacyLocalAdmission(){
            public boolean allowAttempt(String hash,Instant now){return store.allowAttempt(hash,now);}
            public java.util.Optional<AuthStore.Credentials> findByEmail(String value){return store.findByEmail(value);}
            public com.arman.bank.authservice.domain.LegacyMigration.Admission admit(AuthStore.Credentials value,UUID command){return migrations.admitLocal(value.identity().id(),value.passwordHash(),value.credentialGeneration(),value.ownerGeneration(),command);}
        });
    }
    /** Explicit request-owned technical seam. Password checks remain here, never delegated as a boolean. */
    public interface LegacyLocalAdmission {
        boolean allowAttempt(String subjectHash,Instant now);
        java.util.Optional<AuthStore.Credentials> findByEmail(String email);
        com.arman.bank.authservice.domain.LegacyMigration.Admission admit(AuthStore.Credentials captured,UUID commandId);
    }
    public com.arman.bank.authservice.domain.LegacyMigration.Admission beginLegacyMigration(String email,String password,UUID commandId,LegacyLocalAdmission admission){
        java.util.Objects.requireNonNull(admission);
        email=normalizeEmail(email);validatePassword(password);
        if(!admission.allowAttempt(digest(email),clock.instant()))throw new AuthFailure(RATE_LIMITED);
        var credentials=admission.findByEmail(email);
        boolean valid=passwords.verify(password,credentials.map(AuthStore.Credentials::passwordHash).orElse(dummyHash));
        if(!valid||credentials.isEmpty())throw new AuthFailure(UNAUTHORIZED);
        return admission.admit(credentials.get(),commandId);
    }

    public Session login(String email, String password) {
        requireLegacyAdmission();
        email = normalizeEmail(email);
        validatePassword(password);
        limit(email);
        var credentials = store.findByEmail(email);
        boolean valid = passwords.verify(password, credentials.map(AuthStore.Credentials::passwordHash).orElse(dummyHash));
        if (!valid || credentials.isEmpty()) throw new AuthFailure(UNAUTHORIZED);
        provision(credentials.get().identity());
        return createSession(credentials.get().identity().id());
    }

    public Session sso(String token, SsoTokens provider) {
        if (token == null || token.isBlank() || token.length() > 8192 || !token.matches("[A-Za-z0-9._~+/-]+=*"))
            throw new AuthFailure(BAD_INPUT);
        var principal = provider.verify(token);
        if(managed!=null){
            var client=principal.tokenContext().clientId();
            if(client.status()!=SsoTokens.MetadataStatus.PRESENT_VALID_FORMAT)throw new AuthFailure(UNAUTHORIZED);
            if(managed.manages(principal.issuer(),client.value())){
                var session=managed.exchange(token,principal);return new Session(session.accessToken(),session.expiresAt(),true);
            }
            if(!legacyPairs.test(principal.issuer(),client.value()))throw new AuthFailure(UNAUTHORIZED);
            requireLegacyAdmission();
        }
        var identity = store.externalIdentity(principal);
        provision(identity);
        return createSession(identity.id());
    }

    /** Historical 30-minute response contract stays unchanged; managed expiry is never overstated or negative. */
    public long expiresIn(Session session){
        if(!session.boundedLifetime())return 1800;
        long remaining=Duration.between(clock.instant(),session.expiresAt()).getSeconds();
        if(remaining<=0)throw new AuthFailure(UNAUTHORIZED);
        return Math.min(1800,remaining);
    }

    private void provision(Identity identity) {
        store.requireBankingAccess(identity.id());
        // Historical identities remain compatible. Never enroll them from an unverified retry.
        if (identity.registrationPhone() != null) profiles.provision(identity.id(), identity.registrationPhone());
    }

    /** Cutover restricts new banking admission; stored-session continuity is checked separately. */
    private void requireLegacyAdmission() {
        if (managed != null && managed.enabled()) throw new AuthFailure(UNAUTHORIZED);
    }

    public java.util.Optional<AuthStore.EmailContact> emailContact(UUID identityId) {
        return store.emailContact(identityId);
    }

    public static boolean canonicalPhone(String phone) {
        return phone != null && phone.matches("\\+9715[024568][0-9]{7}");
    }

    private Session createSession(UUID identityId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var now = clock.instant();
        var expiry = now.plus(Duration.ofMinutes(30));
        store.saveSession(digest(token), identityId, now, expiry);
        return new Session(token, expiry);
    }

    public Identity me(String authorization) {
        var historical=store.findSession(digest(bearer(authorization)),clock.instant());
        if(historical.isPresent())return historical.get();
        if(managed!=null)return new Identity(managed.validate(authorization),null);
        throw new AuthFailure(UNAUTHORIZED);
    }

    public void logout(String authorization) {
        // Repeated logout is harmless; no identity is accepted from the client.
        store.revoke(digest(bearer(authorization)));
        if(managed!=null&&managed.enabled())managed.logout(authorization);
    }

    private void limit(String email) {
        if (!store.allowAttempt(digest(email), clock.instant())) throw new AuthFailure(RATE_LIMITED);
    }

    public static String normalizeEmail(String email) {
        if (email == null) throw new AuthFailure(BAD_INPUT);
        var normalized = email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+"))
            throw new AuthFailure(BAD_INPUT);
        return normalized;
    }

    private static void validatePassword(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 15 || password.length() > 128)
            throw new AuthFailure(BAD_INPUT);
    }

    private static String bearer(String header) {
        if (header == null || !header.matches("(?i:Bearer) [A-Za-z0-9_-]{43}")) throw new AuthFailure(UNAUTHORIZED);
        return header.substring(7);
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
