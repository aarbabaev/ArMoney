package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.AuthStore;
import com.arman.bank.authservice.application.AuthService;
import com.arman.bank.authservice.application.AuthFailure;
import com.arman.bank.authservice.application.SsoTokens;
import com.arman.bank.authservice.domain.Identity;
import com.arman.bank.runtime.Database;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

public final class PostgresAuthStore implements AuthStore {
    private final Database database;
    private final boolean eligibilityEnforced;
    public PostgresAuthStore(Database database) { this(database, false); }
    public PostgresAuthStore(Database database, boolean eligibilityEnforced) {
        this.database = java.util.Objects.requireNonNull(database);
        this.eligibilityEnforced = eligibilityEnforced;
    }
    private void lockEligibility(org.jooq.DSLContext sql) {
        try { PostgresVerificationProjectionStore.lockControls(sql); }
        catch (com.arman.bank.authservice.domain.VerificationProjection.Failure unavailable) {
            throw new AuthFailure(AuthFailure.Kind.UNAVAILABLE);
        }
    }
    private void requireBankingAccess(org.jooq.DSLContext sql, UUID identityId) {
        PostgresLegacyMigrationStore.lockOwner(sql,identityId);
        PostgresLegacyMigrationStore.requireNoPending(sql,identityId);
        if (sql.fetchOne("select identity_id from managed_registration_principals where identity_id=? union all select identity_id from managed_identity_sources where identity_id=? limit 1", identityId, identityId) != null)
            throw new AuthFailure(AuthFailure.Kind.UNAUTHORIZED);
        if (eligibilityEnforced && sql.fetchOne("select enrollment_id from verification_authorities where mapped_identity_id=? or pending_identity_id=?", identityId, identityId) != null)
            throw new AuthFailure(AuthFailure.Kind.UNAUTHORIZED);
    }
    @Override public void requireBankingAccess(UUID identityId) {
        database.transaction(sql -> { lockEligibility(sql); requireBankingAccess(sql, identityId); return null; });
    }
    private static OffsetDateTime time(Instant value) { return value.atOffset(ZoneOffset.UTC); }

    @Override public Identity externalIdentity(SsoTokens.Principal principal) {
        String issuer = principal.issuer(), subject = principal.subject(), phone = principal.phoneNumber();
        return database.transaction(sql -> {
            lockEligibility(sql);
            var existing = external(sql, issuer, subject);
            if (existing != null) requireBankingAccess(sql, existing.id());
            if (existing != null) return refreshContact(sql, matchingPhone(existing, phone), principal);
            if (!AuthService.canonicalPhone(phone)) throw new AuthFailure(AuthFailure.Kind.UNAUTHORIZED);
            // Staged subjects must finish their own authority transaction, never allocate a different legacy UUID.
            if (sql.fetchOne("select e.id from verification_enrollments e join verification_authorities a on a.enrollment_id=e.id where e.issuer=? and e.subject=?", issuer, subject) != null)
                throw new AuthFailure(AuthFailure.Kind.UNAUTHORIZED);
            if (sql.fetchOne("select e.id from verification_enrollments e join verification_authorities a on a.enrollment_id=e.id where e.phone=?", phone) != null)
                throw new AuthFailure(AuthFailure.Kind.CONFLICT);
            var candidate = UUID.randomUUID();
            var reserved = sql.fetchOne("""
                insert into identities(id, registration_phone) values (?, ?)
                on conflict (registration_phone) do nothing returning id
                """, candidate, phone);
            if (reserved == null) {
                // A concurrent exchange of this same subject may have committed the claim.
                var winner = external(sql, issuer, subject);
                if (winner != null) { requireBankingAccess(sql,winner.id()); return refreshContact(sql, matchingPhone(winner, phone), principal); }
                throw new AuthFailure(AuthFailure.Kind.CONFLICT);
            }
            var inserted = sql.fetchOne("""
                insert into external_identities(issuer, subject, identity_id) values (?, ?, ?)
                on conflict (issuer, subject) do nothing returning identity_id
                """, issuer, subject, candidate);
            if (inserted != null) { requireBankingAccess(sql,candidate); return refreshContact(sql, new Identity(candidate, null, phone), principal); }
            sql.execute("delete from identities where id = ?", candidate);
            var winner=matchingPhone(external(sql,issuer,subject),phone);
            requireBankingAccess(sql,winner.id());
            return refreshContact(sql,winner,principal);
        });
    }

    private static Identity refreshContact(org.jooq.DSLContext sql, Identity identity, SsoTokens.Principal principal) {
        // Every authenticated refresh replaces both fields, including absent/invalid claims.
        // This transaction contains the mapping and phone checks; credential columns are untouched.
        sql.execute("update identities set notification_email = ?, notification_email_verified = ? where id = ?",
            principal.email(), principal.emailVerified(), identity.id());
        return identity;
    }

    @Override public Optional<EmailContact> emailContact(UUID identityId) {
        return database.transaction(sql -> {
            var row = sql.fetchOne("select email, notification_email, notification_email_verified from identities where id = ?", identityId);
            if (row == null) return Optional.empty();
            var contact = row.get("notification_email", String.class);
            return Optional.of(new EmailContact(contact == null ? row.get("email", String.class) : contact,
                contact != null && Boolean.TRUE.equals(row.get("notification_email_verified", Boolean.class))));
        });
    }

    private static Identity external(org.jooq.DSLContext sql, String issuer, String subject) {
        var row = sql.fetchOne("""
            select i.id, i.email, i.registration_phone from identities i
            join external_identities e on e.identity_id = i.id where e.issuer = ? and e.subject = ?
            """, issuer, subject);
        return row == null ? null : identity(row);
    }

    private static Identity matchingPhone(Identity identity, String phone) {
        if (identity == null) throw new AuthFailure(AuthFailure.Kind.UNAVAILABLE);
        if (identity.registrationPhone() != null && !identity.registrationPhone().equals(phone))
            throw new AuthFailure(AuthFailure.Kind.UNAUTHORIZED);
        return identity;
    }

    private static Identity identity(org.jooq.Record row) {
        return new Identity(row.get("id", UUID.class), row.get("email", String.class), row.get("registration_phone", String.class));
    }

    @Override public Identity register(Identity identity, String passwordHash) {
        if (!AuthService.canonicalPhone(identity.registrationPhone())) throw new AuthFailure(AuthFailure.Kind.BAD_INPUT);
        return database.transaction(sql -> {
            lockEligibility(sql);
            var retry = sql.fetchOne("select id,email,registration_phone from identities where email=?", identity.email());
            if (retry != null) {
                PostgresLegacyMigrationStore.lockOwner(sql,retry.get("id",UUID.class));
                PostgresLegacyMigrationStore.requireNoPending(sql,retry.get("id",UUID.class));
                if (identity.registrationPhone().equals(retry.get("registration_phone", String.class))) return identity(retry);
                throw new AuthFailure(AuthFailure.Kind.CONFLICT);
            }
            // Contact equality is conflict denial only; it must never select or link the new identity.
            if (sql.fetchOne("select e.id from verification_enrollments e join verification_authorities a on a.enrollment_id=e.id where e.phone=? or e.email=?", identity.registrationPhone(), identity.email()) != null)
                throw new AuthFailure(AuthFailure.Kind.CONFLICT);
            var inserted = sql.fetchOne("""
                insert into identities(id, email, password_hash, registration_phone) values (?, ?, ?, ?)
                on conflict do nothing returning id, email, registration_phone
                """, identity.id(), identity.email(), passwordHash, identity.registrationPhone());
            if (inserted != null) { PostgresLegacyMigrationStore.lockOwner(sql,inserted.get("id",UUID.class)); return identity(inserted); }
            var existing = sql.fetchOne("select id, email, registration_phone from identities where email = ?", identity.email());
            if (existing != null && identity.registrationPhone().equals(existing.get("registration_phone", String.class))) {
                PostgresLegacyMigrationStore.lockOwner(sql,existing.get("id",UUID.class));
                PostgresLegacyMigrationStore.requireNoPending(sql,existing.get("id",UUID.class));
                return identity(existing);
            }
            throw new AuthFailure(AuthFailure.Kind.CONFLICT);
        });
    }

    @Override public Optional<Credentials> findByEmail(String email){return database.transaction(sql->findByEmail(sql,email));}
    static Optional<Credentials> findByEmail(org.jooq.DSLContext sql,String email){
            var row = sql.fetchOne("select i.id,i.email,i.password_hash,i.registration_phone,g.credential_generation,g.owner_generation from identities i join legacy_auth_generations g on g.identity_id=i.id where i.email = ?", email);
            return row == null ? Optional.empty() : Optional.of(new Credentials(
                identity(row), row.get("password_hash", String.class),row.get("credential_generation",Long.class),row.get("owner_generation",Long.class)));
    }

    @Override public boolean allowAttempt(String subjectHash,Instant now){return database.transaction(sql->allowAttempt(sql,subjectHash,now));}
    static boolean allowAttempt(org.jooq.DSLContext sql,String subjectHash,Instant now){
            var cutoff = time(now.minusSeconds(900));
            sql.execute("delete from auth_attempts where window_start <= cast(? as timestamptz)", cutoff);
            var row = sql.fetchOne("""
                insert into auth_attempts(subject_hash, window_start, attempts) values (?, cast(? as timestamptz), 1)
                on conflict(subject_hash) do update set attempts = least(auth_attempts.attempts + 1, 11)
                returning attempts
                """, subjectHash, time(now));
            return row.get("attempts", Integer.class) <= 10;
    }

    @Override public void saveSession(String tokenHash, UUID identityId, Instant now, Instant expiresAt) {
        database.transaction(sql -> {
            lockEligibility(sql);
            requireBankingAccess(sql, identityId);
            sql.execute("delete from sessions where expires_at <= cast(? as timestamptz)", time(now));
            return sql.execute("insert into sessions(token_hash, identity_id, created_at, expires_at) values (?, ?, cast(? as timestamptz), cast(? as timestamptz))",
                tokenHash, identityId, time(now), time(expiresAt));
        });
    }

    @Override public Optional<Identity> findSession(String tokenHash, Instant now) {
        return database.transaction(sql -> {
            lockEligibility(sql);
            var row = sql.fetchOne("""
                select i.id, i.email, i.registration_phone, s.created_at as session_created, s.expires_at as session_expiry from identities i join sessions s on s.identity_id = i.id
                where s.token_hash = ? and s.expires_at > cast(? as timestamptz)
                """, tokenHash, time(now));
            if (row == null) return Optional.empty();
            UUID owner=row.get("id",UUID.class);
            PostgresLegacyMigrationStore.lockOwner(sql,owner);
            var live=sql.fetchOne("select created_at,expires_at from sessions where token_hash=? and identity_id=? for update",tokenHash,owner);
            if(live==null||!live.get("expires_at",OffsetDateTime.class).toInstant().isAfter(now))return Optional.empty();
            if(PostgresLegacyMigrationStore.pending(sql,owner)!=null)
                PostgresLegacyMigrationStore.requireContinuity(sql,owner,tokenHash,live.get("created_at",OffsetDateTime.class).toInstant(),live.get("expires_at",OffsetDateTime.class).toInstant(),eligibilityEnforced);
            else {
                // Existing live sessions survive authority staging until atomic FINAL.
                // Permanent managed classification still forbids historical bank sessions;
                // requireBankingAccess remains the stricter gate for every new admission.
                if (sql.fetchOne("select identity_id from managed_registration_principals where identity_id=? union all select identity_id from managed_identity_sources where identity_id=? limit 1", owner, owner) != null)
                    throw new AuthFailure(AuthFailure.Kind.UNAUTHORIZED);
            }
            return Optional.of(identity(row));
        });
    }

    @Override public void revoke(String tokenHash) {
        database.transaction(sql -> {
            lockEligibility(sql);
            var session=sql.fetchOne("select identity_id from sessions where token_hash=?",tokenHash);
            if(session!=null)PostgresLegacyMigrationStore.lockOwner(sql,session.get("identity_id",UUID.class));
            return sql.execute("delete from sessions where token_hash = ?", tokenHash);
        });
    }
    @Override public void revokeOwnerSessions(UUID identityId){
        database.transaction(sql->{
            lockEligibility(sql);PostgresLegacyMigrationStore.lockOwner(sql,identityId);
            // SQL checked bigint overflow aborts the whole security-generation/revocation transaction.
            sql.execute("update legacy_auth_generations set owner_generation=owner_generation+1 where identity_id=?",identityId);
            return sql.execute("delete from sessions where identity_id=?",identityId);
        });
    }
}
