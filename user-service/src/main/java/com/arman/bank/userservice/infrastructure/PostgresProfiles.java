package com.arman.bank.userservice.infrastructure;
import com.arman.bank.userservice.application.ProfileStore;
import com.arman.bank.userservice.application.LookupLimitExceeded;
import com.arman.bank.userservice.application.RegistrationConflict;
import com.arman.bank.userservice.domain.Profile;
import com.arman.bank.runtime.Database;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PostgresProfiles implements ProfileStore {
    private final Database database;
    private final ProfileShards shards;
    public PostgresProfiles(Database database) { this(new ProfileShards(Map.of("primary", database), Map.of(), "primary")); }
    public PostgresProfiles(ProfileShards shards) { this.shards = shards; this.database = shards.primary(); }
    // Serialize central phone claims and placement creation; never hold this lock over shard IO.
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
                sql.fetchOne("select identity_id from profile_directory where phone_number = ? and identity_id <> ?", phone, identity) != null)
                throw new RegistrationConflict();
            Placement placed = placement(sql.fetchOne("select * from profile_directory where identity_id = ?", identity));
            if (placed != null && placed.phone() != null && !phone.equals(placed.phone())) throw new RegistrationConflict();
            sql.execute("insert into registration_phone_claims(identity_id, phone_number) values (?, ?) on conflict(identity_id) do nothing", identity, phone);
            sql.execute("update profile_directory set phone_number = ? where identity_id = ? and phone_number is null", phone, identity);
            if (placed != null && placed.shard().equals("primary")) sql.execute(
                "update profiles set phone_number = ? where identity_id = ? and phone_number is null", phone, identity);
            return null;
        });
    }
    public Profile save(Profile profile) { return save(profile, null); }
    public Profile save(Profile profile, String email) {
        String selected = shards.select(email); // Validate even when placement already exists.
        Placement placement = database.transaction(sql -> {
            lockRegistration(sql);
            sql.execute("""
                insert into profile_directory(identity_id, profile_id, shard_id, phone_number)
                values (?, ?, ?, (select phone_number from registration_phone_claims where identity_id = ?))
                on conflict(identity_id) do nothing
                """, profile.identityId(), profile.id(), selected, profile.identityId());
            return placement(sql.fetchOne("select * from profile_directory where identity_id = ?", profile.identityId()));
        });
        // No primary transaction is held over shard IO. UUID and placement survive lost responses.
        Profile saved = shards.database(placement.shard()).transaction(sql -> read(sql.fetchOne("""
            insert into profiles(id, identity_id, display_name) values (?, ?, ?)
            on conflict(identity_id) do update set display_name = excluded.display_name
            returning *
            """, placement.id(), profile.identityId(), profile.displayName())));
        if (!saved.id().equals(placement.id())) throw new IllegalStateException("Profile placement mismatch");
        Placement confirmed = database.transaction(sql -> {
            lockRegistration(sql);
            Placement current = placement(sql.fetchOne(
                "update profile_directory set initialized = true where identity_id = ? returning *", profile.identityId()));
            if (current.shard().equals("primary")) sql.execute(
                "update profiles set phone_number = ?, phone_verified = ? where identity_id = ?", current.phone(), current.verified(), profile.identityId());
            return current;
        });
        return overlay(saved, confirmed);
    }
    public Optional<Profile> find(UUID identity) {
        Placement placed = lookup(identity);
        if (placed == null || !placed.initialized()) return Optional.empty();
        return Optional.of(load(identity, placed));
    }
    private Placement lookup(UUID identity) {
        return database.transaction(sql -> placement(sql.fetchOne("select * from profile_directory where identity_id = ?", identity)));
    }
    private Profile load(UUID identity, Placement placed) {
        Profile result = shards.database(placed.shard()).transaction(sql -> {
            var row = sql.fetchOne("select * from profiles where identity_id = ? and id = ?", identity, placed.id());
            if (row == null) throw new IllegalStateException("Pinned profile unavailable");
            return read(row);
        });
        return overlay(result, placed);
    }
    public Optional<Profile> changePhone(UUID identity, String phone) {
        Profile.validatePhone(phone);
        boolean matching = database.transaction(sql -> sql.fetchOne(
            "select identity_id from registration_phone_claims where identity_id = ? and phone_number = ?", identity, phone) != null);
        if (!matching) throw new RegistrationConflict();
        Optional<Profile> existing = find(identity);
        if (existing.isPresent() && !phone.equals(existing.get().phoneNumber())) throw new RegistrationConflict();
        return existing;
    }
    public Optional<Profile> resolvePhone(UUID requester, String phone) {
        Profile.validatePhone(phone);
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
        var row = registeredPlacement(phone);
        if (row == null) return Optional.empty();
        UUID identity = row.get("identity_id", UUID.class);
        Profile profile = load(identity, placement(row));
        // Recheck after shard IO; never disclose a recipient from a stale central claim/directory match.
        var current = registeredPlacement(phone);
        return current != null && identity.equals(current.get("identity_id", UUID.class))
            ? Optional.of(overlay(profile, placement(current))) : Optional.empty();
    }
    private org.jooq.Record registeredPlacement(String phone) {
        return database.transaction(sql -> sql.fetchOne("""
            select d.* from profile_directory d join registration_phone_claims c
                on c.identity_id = d.identity_id and c.phone_number = d.phone_number
            where c.phone_number = ? and d.initialized
            """, phone));
    }
    /** Operator-only entry point: the HTTP service never invokes this method. */
    public void verifyPendingPhone(UUID identity, String expectedPhone, String operator, String evidence) {
        Profile.validatePhone(expectedPhone);
        if (operator == null || !operator.matches("[A-Za-z0-9._-]{1,100}") || evidence == null || !evidence.matches("[A-Za-z0-9._:/-]{1,200}"))
            throw new IllegalArgumentException("Operator and evidence references required");
        database.transaction(sql -> {
            Placement placed = placement(sql.fetchOne("select * from profile_directory where identity_id = ? for update", identity));
            if (placed == null || !placed.initialized() || !expectedPhone.equals(placed.phone()) || placed.verified())
                throw new IllegalStateException("Expected pending phone no longer matches");
            sql.execute("update profile_directory set phone_verified = true where identity_id = ?", identity);
            if (placed.shard().equals("primary")) sql.execute("update profiles set phone_verified = true where identity_id = ?", identity);
            sql.execute("insert into phone_verification_audit(id, identity_id, phone_number, operator_reference, evidence_reference) values (?, ?, ?, ?, ?)",
                UUID.randomUUID(), identity, expectedPhone, operator, evidence);
            return null;
        });
    }
    private record Placement(UUID id, String shard, boolean initialized, String phone, boolean verified) {}
    private static Placement placement(org.jooq.Record row) {
        return row == null ? null : new Placement(row.get("profile_id", UUID.class), row.get("shard_id", String.class),
            row.get("initialized", Boolean.class), row.get("phone_number", String.class), row.get("phone_verified", Boolean.class));
    }
    private static Profile overlay(Profile profile, Placement placed) {
        return new Profile(profile.id(), profile.identityId(), profile.displayName(), placed.phone(), placed.verified());
    }
    private static Profile read(org.jooq.Record row) {
        return new Profile(row.get("id", UUID.class), row.get("identity_id", UUID.class), row.get("display_name", String.class),
            row.get("phone_number", String.class), row.get("phone_verified", Boolean.class));
    }
}

