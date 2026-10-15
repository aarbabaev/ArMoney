package com.arman.bank.userservice.infrastructure;

import com.arman.bank.runtime.Database;
import com.arman.bank.userservice.application.VerificationProjectionStore;
import com.arman.bank.userservice.domain.VerificationProjection;
import org.jooq.DSLContext;
import org.jooq.Record;
import java.util.Objects;
import java.util.UUID;

/** Uses only the primary user database. All central writers share the registration lock. */
public final class PostgresVerificationProjectionStore implements VerificationProjectionStore {
    private final Database database;
    private final int retainedCapacity;
    private final int tombstoneCapacity;
    public PostgresVerificationProjectionStore(Database primary) { this(primary, 100000, 1000000); }
    public PostgresVerificationProjectionStore(Database primary, int retainedCapacity, int tombstoneCapacity) {
        if (retainedCapacity < 1 || retainedCapacity > 100000 || tombstoneCapacity < 1 || tombstoneCapacity > 1000000)
            throw new IllegalArgumentException("Invalid capacity");
        this.database = Objects.requireNonNull(primary);
        this.retainedCapacity = retainedCapacity;
        this.tombstoneCapacity = tombstoneCapacity;
    }
    private static void lock(DSLContext sql) { sql.fetch("select pg_advisory_xact_lock(725631904128::bigint)"); }
    private static void writer(String writer) {
        if (writer == null || !writer.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("Invalid writer");
    }
    @Override public boolean admit(String writer) {
        writer(writer);
        return database.transaction(sql -> {
            lock(sql);
            sql.execute("""
                insert into verification_projection_writer_budgets(writer_id,tokens,updated_at)
                select ?,20,clock_timestamp() where
                  exists(select 1 from verification_projection_writer_budgets where writer_id=?)
                  or (select count(*) from verification_projection_writer_budgets)<16
                on conflict(writer_id) do nothing
                """, writer, writer);
            return sql.fetchOne("""
                update verification_projection_writer_budgets set
                  tokens=least(20,tokens+greatest(0,extract(epoch from (clock_timestamp()-updated_at)))*2)-1,
                  updated_at=clock_timestamp()
                where writer_id=? and least(20,tokens+greatest(0,extract(epoch from (clock_timestamp()-updated_at)))*2)>=1
                returning writer_id
                """, writer) != null;
        });
    }
    @Override public int cleanup() { return database.transaction(sql -> { lock(sql); return cleanup(sql); }); }
    private static int cleanup(DSLContext sql) {
        return sql.execute("""
            delete from verification_projection_operations where (writer_id,operation_id) in
              (select writer_id,operation_id from verification_projection_operations
               where expires_at<=clock_timestamp() order by expires_at,writer_id,operation_id limit 100)
            """);
    }
    @Override public VerificationProjection.Ack commit(String writer, VerificationProjection command) {
        writer(writer);
        Objects.requireNonNull(command);
        try {
            return database.transaction(sql -> apply(sql, writer, command));
        } catch (org.jooq.exception.DataAccessException e) {
            if ("23505".equals(e.sqlState())) throw new Rejected(409);
            throw new Rejected(503);
        }
    }
    private VerificationProjection.Ack apply(DSLContext sql, String writer, VerificationProjection c) {
        lock(sql);
        Record retained = sql.fetchOne("select *,expires_at>clock_timestamp() as live from verification_projection_operations where writer_id=? and operation_id=?", writer, c.operationId());
        if (retained != null && Boolean.TRUE.equals(retained.get("live", Boolean.class))) {
            if (!command(retained).equals(c)) throw new Rejected(409);
            return command(retained).ack();
        }
        if (sql.fetchOne("select operation_id from verification_projection_tombstones where writer_id=? and operation_id=?", writer, c.operationId()) != null)
            throw new Rejected(410);
        Record current = sql.fetchOne("select * from verification_projection_current where identity_id=? for update", c.identityId());
        if (c.expectedVersion() != (current == null ? 0 : current.get("version", Integer.class))) throw new Rejected(409);
        if (current != null) {
            boolean same = c.canonicalPhone().equals(current.get("canonical_phone", String.class));
            int contact = current.get("phone_contact_version", Integer.class);
            if (c.verificationEpoch() <= current.get("verification_epoch", Long.class)
                    || (same ? c.phoneContactVersion() != contact : c.phoneContactVersion() <= contact)) throw new Rejected(409);
        }
        Record claim = sql.fetchOne("select * from registration_phone_claims where identity_id=? for update", c.identityId());
        Record directory = sql.fetchOne("select * from profile_directory where identity_id=? for update", c.identityId());
        String oldPhone = current == null ? c.canonicalPhone() : current.get("canonical_phone", String.class);
        if ((claim != null && !oldPhone.equals(claim.get("phone_number", String.class)))
                || (current != null && claim == null)
                || (directory != null && directory.get("phone_number") != null && !oldPhone.equals(directory.get("phone_number", String.class)))) throw new Rejected(409);
        if (sql.fetchOne("select identity_id from registration_phone_claims where phone_number=? and identity_id<>?", c.canonicalPhone(), c.identityId()) != null
                || sql.fetchOne("select identity_id from profile_directory where phone_number=? and identity_id<>?", c.canonicalPhone(), c.identityId()) != null) throw new Rejected(409);
        cleanup(sql);
        if (sql.fetchOne("select count(*) from verification_projection_operations").get(0, Long.class) >= retainedCapacity
                || sql.fetchOne("select count(*) from verification_projection_tombstones").get(0, Long.class) >= tombstoneCapacity) throw new Rejected(429);
        boolean verified = directory != null && c.canonicalPhone().equals(directory.get("phone_number", String.class))
                && Boolean.TRUE.equals(directory.get("phone_verified", Boolean.class));
        if (directory != null && "primary".equals(directory.get("shard_id", String.class))) {
            Record physical = sql.fetchOne("select * from profiles where identity_id=? for update", c.identityId());
            if (physical == null && Boolean.TRUE.equals(directory.get("initialized", Boolean.class))) throw new Rejected(409);
            if (physical != null) {
                boolean pendingInsert = !Boolean.TRUE.equals(directory.get("initialized", Boolean.class))
                    && physical.get("phone_number") == null && !Boolean.TRUE.equals(physical.get("phone_verified", Boolean.class));
                if (!Objects.equals(physical.get("id", UUID.class), directory.get("profile_id", UUID.class))
                        || (!pendingInsert && (!Objects.equals(physical.get("phone_number", String.class), directory.get("phone_number", String.class))
                        || !Objects.equals(physical.get("phone_verified", Boolean.class), directory.get("phone_verified", Boolean.class))))) throw new Rejected(409);
                sql.execute("update profiles set phone_number=?,phone_verified=? where identity_id=?", c.canonicalPhone(), verified, c.identityId());
            }
        }
        sql.execute("""
            insert into registration_phone_claims(identity_id,phone_number) values (?,?)
            on conflict(identity_id) do update set phone_number=excluded.phone_number
            """, c.identityId(), c.canonicalPhone());
        sql.execute("update profile_directory set phone_number=?,phone_verified=? where identity_id=?", c.canonicalPhone(), verified, c.identityId());
        sql.execute("""
            insert into verification_projection_current(identity_id,version,canonical_phone,phone_contact_version,verification_epoch)
            values (?,?,?,?,?) on conflict(identity_id) do update set version=excluded.version,
              canonical_phone=excluded.canonical_phone,phone_contact_version=excluded.phone_contact_version,
              verification_epoch=excluded.verification_epoch
            """, c.identityId(), c.version(), c.canonicalPhone(), c.phoneContactVersion(), c.verificationEpoch());
        sql.execute("insert into verification_projection_tombstones(writer_id,operation_id,payload_hash,expires_at) values (?,?,?,clock_timestamp()+interval '7 days')", writer, c.operationId(), c.payloadHash());
        sql.execute("""
            insert into verification_projection_operations(writer_id,operation_id,identity_id,expected_version,version,
              canonical_phone,phone_contact_version,verification_epoch,payload_hash,expires_at)
            select ?,?,?,?,?,?,?,?,?,expires_at from verification_projection_tombstones where writer_id=? and operation_id=?
            """, writer, c.operationId(), c.identityId(), c.expectedVersion(), c.version(), c.canonicalPhone(), c.phoneContactVersion(), c.verificationEpoch(), c.payloadHash(), writer, c.operationId());
        return c.ack();
    }
    private static VerificationProjection command(Record r) {
        return new VerificationProjection(r.get("operation_id", UUID.class),r.get("identity_id", UUID.class),
            r.get("expected_version", Integer.class),r.get("version", Integer.class),r.get("canonical_phone", String.class),
            r.get("phone_contact_version", Integer.class),r.get("verification_epoch", Long.class),r.get("payload_hash", String.class));
    }
}
