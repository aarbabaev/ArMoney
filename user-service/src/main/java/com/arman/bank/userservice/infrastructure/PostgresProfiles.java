package com.arman.bank.userservice.infrastructure;
import com.arman.bank.userservice.application.ProfileStore;
import com.arman.bank.userservice.domain.Profile;
import com.arman.bank.runtime.Database;
import java.util.Optional;
import java.util.UUID;
public final class PostgresProfiles implements ProfileStore {
    private final Database database;
    public PostgresProfiles(Database database) { this.database = database; }
    public Profile save(Profile profile) {
        return database.transaction(sql -> read(sql.fetchOne("""
            insert into profiles(id, identity_id, display_name) values (?, ?, ?)
            on conflict(identity_id) do update set display_name = excluded.display_name
            returning id, identity_id, display_name
            """, profile.id(), profile.identityId(), profile.displayName())));
    }
    public Optional<Profile> find(UUID identity) {
        return database.transaction(sql -> Optional.ofNullable(sql.fetchOne(
            "select id, identity_id, display_name from profiles where identity_id = ?", identity)).map(PostgresProfiles::read));
    }
    private static Profile read(org.jooq.Record row) {
        return new Profile(row.get("id", UUID.class), row.get("identity_id", UUID.class), row.get("display_name", String.class));
    }
}
