package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.AuthStore;
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

    @Override public Identity externalIdentity(String issuer, String subject) {
        return database.transaction(sql -> {
            var existing = sql.fetchOne("select identity_id from external_identities where issuer = ? and subject = ?", issuer, subject);
            if (existing != null) return new Identity(existing.get("identity_id", UUID.class), null);
            var candidate = UUID.randomUUID();
            sql.execute("insert into identities(id) values (?)", candidate);
            var inserted = sql.fetchOne("""
                insert into external_identities(issuer, subject, identity_id) values (?, ?, ?)
                on conflict (issuer, subject) do nothing returning identity_id
                """, issuer, subject, candidate);
            if (inserted != null) return new Identity(candidate, null);
            sql.execute("delete from identities where id = ?", candidate);
            var winner = sql.fetchOne("select identity_id from external_identities where issuer = ? and subject = ?", issuer, subject);
            return new Identity(winner.get("identity_id", UUID.class), null);
        });
    }

    @Override public void register(Identity identity, String passwordHash) {
        database.transaction(sql -> sql.execute(
            "insert into identities(id, email, password_hash) values (?, ?, ?) on conflict (email) do nothing",
            identity.id(), identity.email(), passwordHash));
    }

    @Override public Optional<Credentials> findByEmail(String email) {
        return database.transaction(sql -> {
            var row = sql.fetchOne("select id, email, password_hash from identities where email = ?", email);
            return row == null ? Optional.empty() : Optional.of(new Credentials(
                new Identity(row.get("id", UUID.class), row.get("email", String.class)), row.get("password_hash", String.class)));
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
                select i.id, i.email from identities i join sessions s on s.identity_id = i.id
                where s.token_hash = ? and s.expires_at > cast(? as timestamptz)
                """, tokenHash, time(now));
            return row == null ? Optional.empty() : Optional.of(new Identity(row.get("id", UUID.class), row.get("email", String.class)));
        });
    }

    @Override public void revoke(String tokenHash) {
        database.transaction(sql -> sql.execute("delete from sessions where token_hash = ?", tokenHash));
    }
}
