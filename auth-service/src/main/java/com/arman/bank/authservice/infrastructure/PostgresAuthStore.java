package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.AuthStore;
import com.arman.bank.authservice.application.AuthService;
import com.arman.bank.authservice.application.AuthFailure;
import com.arman.bank.authservice.domain.Identity;
import com.arman.bank.runtime.Database;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

public final class PostgresAuthStore implements AuthStore {
    private final Database database;
    public PostgresAuthStore(Database database) { this.database = database; }
    private static OffsetDateTime time(Instant value) { return value.atOffset(ZoneOffset.UTC); }

    @Override public Identity externalIdentity(String issuer, String subject, String phone) {
        return database.transaction(sql -> {
            var existing = external(sql, issuer, subject);
            if (existing != null) return matchingPhone(existing, phone);
            if (!AuthService.canonicalPhone(phone)) throw new AuthFailure(AuthFailure.Kind.UNAUTHORIZED);
            var candidate = UUID.randomUUID();
            var reserved = sql.fetchOne("""
                insert into identities(id, registration_phone) values (?, ?)
                on conflict (registration_phone) do nothing returning id
                """, candidate, phone);
            if (reserved == null) {
                // A concurrent exchange of this same subject may have committed the claim.
                var winner = external(sql, issuer, subject);
                if (winner != null) return matchingPhone(winner, phone);
                throw new AuthFailure(AuthFailure.Kind.CONFLICT);
            }
            var inserted = sql.fetchOne("""
                insert into external_identities(issuer, subject, identity_id) values (?, ?, ?)
                on conflict (issuer, subject) do nothing returning identity_id
                """, issuer, subject, candidate);
            if (inserted != null) return new Identity(candidate, null, phone);
            sql.execute("delete from identities where id = ?", candidate);
            return matchingPhone(external(sql, issuer, subject), phone);
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
            var inserted = sql.fetchOne("""
                insert into identities(id, email, password_hash, registration_phone) values (?, ?, ?, ?)
                on conflict do nothing returning id, email, registration_phone
                """, identity.id(), identity.email(), passwordHash, identity.registrationPhone());
            if (inserted != null) return identity(inserted);
            var existing = sql.fetchOne("select id, email, registration_phone from identities where email = ?", identity.email());
            if (existing != null && identity.registrationPhone().equals(existing.get("registration_phone", String.class)))
                return identity(existing);
            throw new AuthFailure(AuthFailure.Kind.CONFLICT);
        });
    }

    @Override public Optional<Credentials> findByEmail(String email) {
        return database.transaction(sql -> {
            var row = sql.fetchOne("select id, email, password_hash, registration_phone from identities where email = ?", email);
            return row == null ? Optional.empty() : Optional.of(new Credentials(
                identity(row), row.get("password_hash", String.class)));
        });
    }

    @Override public boolean allowAttempt(String subjectHash, Instant now) {
        return database.transaction(sql -> {
            var cutoff = time(now.minusSeconds(900));
            sql.execute("delete from auth_attempts where window_start <= cast(? as timestamptz)", cutoff);
            var row = sql.fetchOne("""
                insert into auth_attempts(subject_hash, window_start, attempts) values (?, cast(? as timestamptz), 1)
                on conflict(subject_hash) do update set attempts = least(auth_attempts.attempts + 1, 11)
                returning attempts
                """, subjectHash, time(now));
            return row.get("attempts", Integer.class) <= 10;
        });
    }

    @Override public void saveSession(String tokenHash, UUID identityId, Instant now, Instant expiresAt) {
        database.transaction(sql -> {
            sql.execute("delete from sessions where expires_at <= cast(? as timestamptz)", time(now));
            return sql.execute("insert into sessions(token_hash, identity_id, created_at, expires_at) values (?, ?, cast(? as timestamptz), cast(? as timestamptz))",
                tokenHash, identityId, time(now), time(expiresAt));
        });
    }

    @Override public Optional<Identity> findSession(String tokenHash, Instant now) {
        return database.transaction(sql -> {
            var row = sql.fetchOne("""
                select i.id, i.email, i.registration_phone from identities i join sessions s on s.identity_id = i.id
                where s.token_hash = ? and s.expires_at > cast(? as timestamptz)
                """, tokenHash, time(now));
            return row == null ? Optional.empty() : Optional.of(identity(row));
        });
    }

    @Override public void revoke(String tokenHash) {
        database.transaction(sql -> sql.execute("delete from sessions where token_hash = ?", tokenHash));
    }
}
