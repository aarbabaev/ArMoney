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
            sql.execute("delete from auth_attempts where window_start <= ?", cutoff);
            var row = sql.fetchOne("""
                insert into auth_attempts(subject_hash, window_start, attempts) values (?, ?, 1)
                on conflict(subject_hash) do update set attempts = least(auth_attempts.attempts + 1, 11)
                returning attempts
                """, subjectHash, time(now));
            return row.get("attempts", Integer.class) <= 10;
        });
    }

    @Override public void saveSession(String tokenHash, UUID identityId, Instant now, Instant expiresAt) {
        database.transaction(sql -> {
            sql.execute("delete from sessions where expires_at <= ?", time(now));
            return sql.execute("insert into sessions(token_hash, identity_id, created_at, expires_at) values (?, ?, ?, ?)",
                tokenHash, identityId, time(now), time(expiresAt));
        });
    }

    @Override public Optional<Identity> findSession(String tokenHash, Instant now) {
        return database.transaction(sql -> {
            var row = sql.fetchOne("""
                select i.id, i.email from identities i join sessions s on s.identity_id = i.id
                where s.token_hash = ? and s.expires_at > ?
                """, tokenHash, time(now));
            return row == null ? Optional.empty() : Optional.of(new Identity(row.get("id", UUID.class), row.get("email", String.class)));
        });
    }

    @Override public void revoke(String tokenHash) {
        database.transaction(sql -> sql.execute("delete from sessions where token_hash = ?", tokenHash));
    }
}
