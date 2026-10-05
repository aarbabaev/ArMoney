package com.arman.bank.userservice.infrastructure;
import com.arman.bank.userservice.application.ProfileStore;
import com.arman.bank.userservice.application.LookupLimitExceeded;
import com.arman.bank.userservice.application.RegistrationConflict;
import com.arman.bank.userservice.domain.Profile;
import com.arman.bank.runtime.Database;
import java.util.Optional;
import java.util.UUID;
public final class PostgresProfiles implements ProfileStore {
    private final Database database;
    public PostgresProfiles(Database database) { this.database = database; }
    // All profile/claim writers take this lock before reading either table.
    private static void lockRegistration(org.jooq.DSLContext sql) {
        sql.fetch("select pg_advisory_xact_lock(725631904128::bigint)");
    }
    public void registerPhone(UUID identity, String phone) {
        Profile.validatePhone(phone);
        database.transaction(sql -> {
            lockRegistration(sql);
            var claim = sql.fetchOne("select phone_number from registration_phone_claims where identity_id = ?", identity);
            if (claim != null && !phone.equals(claim.get("phone_number", String.class))) throw new RegistrationConflict();
            if (sql.fetchOne("select identity_id from registration_phone_claims where phone_number = ? and identity_id <> ?", phone, identity) != null ||
                sql.fetchOne("select identity_id from profiles where phone_number = ? and identity_id <> ?", phone, identity) != null)
                throw new RegistrationConflict();
            var profile = sql.fetchOne("select phone_number from profiles where identity_id = ?", identity);
            if (profile != null && profile.get("phone_number") != null && !phone.equals(profile.get("phone_number", String.class)))
                throw new RegistrationConflict();
            sql.execute("insert into registration_phone_claims(identity_id, phone_number) values (?, ?) on conflict(identity_id) do nothing", identity, phone);
            sql.execute("update profiles set phone_number = ? where identity_id = ? and phone_number is null", phone, identity);
            return null;
        });
    }
    public Profile save(Profile profile) {
        return database.transaction(sql -> {
            lockRegistration(sql);
            return read(sql.fetchOne("""
                insert into profiles(id, identity_id, display_name, phone_number)
                values (?, ?, ?, (select phone_number from registration_phone_claims where identity_id = ?))
                on conflict(identity_id) do update set display_name = excluded.display_name
                returning *
                """, profile.id(), profile.identityId(), profile.displayName(), profile.identityId()));
        });
    }
    public Optional<Profile> find(UUID identity) {
        return database.transaction(sql -> Optional.ofNullable(sql.fetchOne(
            "select * from profiles where identity_id = ?", identity)).map(PostgresProfiles::read));
    }
    public Optional<Profile> changePhone(UUID identity, String phone) {
        Profile.validatePhone(phone);
        return database.transaction(sql -> {
            lockRegistration(sql);
            var claim = sql.fetchOne("select phone_number from registration_phone_claims where identity_id = ?", identity);
            if (claim == null || !phone.equals(claim.get("phone_number", String.class))) throw new RegistrationConflict();
            return Optional.ofNullable(sql.fetchOne("select * from profiles where identity_id = ?", identity)).map(PostgresProfiles::read);
        });
    }
    public Optional<Profile> resolvePhone(UUID requester, String phone) {
        Profile.validatePhone(phone);
        // Consume quota even when the number is absent. Counter upsert serializes concurrent callers.
        boolean allowed = database.transaction(sql -> {
            sql.execute("delete from phone_lookup_limits where requester_id in (select requester_id from phone_lookup_limits where window_start < current_timestamp - interval '1 day' limit 100)");
            return sql.fetchOne("""
                insert into phone_lookup_limits(requester_id, window_start, attempts) values (?, current_timestamp, 1)
                on conflict(requester_id) do update set
                  window_start = case when phone_lookup_limits.window_start <= current_timestamp - interval '60 seconds' then current_timestamp else phone_lookup_limits.window_start end,
                  attempts = case when phone_lookup_limits.window_start <= current_timestamp - interval '60 seconds' then 1 else phone_lookup_limits.attempts + 1 end
                where phone_lookup_limits.window_start <= current_timestamp - interval '60 seconds' or phone_lookup_limits.attempts < 30
                returning attempts
                """, requester) != null;
        });
        if (!allowed) throw new LookupLimitExceeded();
        return database.transaction(sql -> Optional.ofNullable(sql.fetchOne(
            "select p.* from profiles p join registration_phone_claims c on c.identity_id = p.identity_id and c.phone_number = p.phone_number where c.phone_number = ?", phone)).map(PostgresProfiles::read));
    }
    /** Operator-only entry point: the HTTP service never invokes this method. */
    public void verifyPendingPhone(UUID identity, String expectedPhone, String operator, String evidence) {
        Profile.validatePhone(expectedPhone);
        if (operator == null || !operator.matches("[A-Za-z0-9._-]{1,100}") || evidence == null || !evidence.matches("[A-Za-z0-9._:/-]{1,200}"))
            throw new IllegalArgumentException("Operator and evidence references required");
        database.transaction(sql -> {
            var row = sql.fetchOne("select * from profiles where identity_id = ? for update", identity);
            if (row == null || !expectedPhone.equals(row.get("phone_number", String.class)) || row.get("phone_verified", Boolean.class))
                throw new IllegalStateException("Expected pending phone no longer matches");
            sql.execute("update profiles set phone_verified = true where identity_id = ?", identity);
            sql.execute("insert into phone_verification_audit(id, identity_id, phone_number, operator_reference, evidence_reference) values (?, ?, ?, ?, ?)",
                UUID.randomUUID(), identity, expectedPhone, operator, evidence);
            return null;
        });
    }
    private static Profile read(org.jooq.Record row) {
        return new Profile(row.get("id", UUID.class), row.get("identity_id", UUID.class), row.get("display_name", String.class),
            row.get("phone_number", String.class), row.get("phone_verified", Boolean.class));
    }
}
