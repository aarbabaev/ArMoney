package com.arman.bank.userservice.infrastructure;

import com.arman.bank.runtime.Database;
import com.arman.bank.userservice.application.VerificationProjectionStore;
import com.arman.bank.userservice.domain.VerificationProjection;
import com.arman.bank.userservice.domain.LiveUserRevision;
import com.arman.bank.userservice.application.UserMutationSuspensionPort;
import com.arman.bank.userservice.application.LiveUserCurrentStore;
import org.jooq.DSLContext;
import org.jooq.Record;
import java.util.Objects;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.nio.charset.StandardCharsets;

/** Uses only the primary user database. All central writers share the registration lock. */
public final class PostgresVerificationProjectionStore implements VerificationProjectionStore {
    private final Database database;
    private final int retainedCapacity;
    private final int tombstoneCapacity;
    private final UserMutationSuspensionPort suspension;
    private final String mutationWriter;
    public PostgresVerificationProjectionStore(Database primary) { this(primary, 100000, 1000000); }
    public PostgresVerificationProjectionStore(Database primary, int retainedCapacity, int tombstoneCapacity) {
        this(primary,retainedCapacity,tombstoneCapacity,null,null);
    }
    /** Explicit composition only. No unknown-source fallback is allowed in this mode. */
    public PostgresVerificationProjectionStore(Database primary,UserMutationSuspensionPort suspension,String mutationWriter) {
        this(primary,100000,1000000,Objects.requireNonNull(suspension),mutationWriter);
    }
    private PostgresVerificationProjectionStore(Database primary,int retainedCapacity,int tombstoneCapacity,UserMutationSuspensionPort suspension,String mutationWriter) {
        if (retainedCapacity < 1 || retainedCapacity > 100000 || tombstoneCapacity < 1 || tombstoneCapacity > 1000000)
            throw new IllegalArgumentException("Invalid capacity");
        this.database = Objects.requireNonNull(primary);
        this.retainedCapacity = retainedCapacity;
        this.tombstoneCapacity = tombstoneCapacity;
        this.suspension=suspension;this.mutationWriter=mutationWriter;
        if(suspension!=null&&(mutationWriter==null||!mutationWriter.matches("[A-Za-z0-9._-]{1,64}")))throw new IllegalArgumentException("Live User writer rejected");
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
        return commit(writer,command,System.nanoTime());
    }
    @Override public VerificationProjection.Ack commit(String writer,VerificationProjection command,long originalStartedNanos) {
        writer(writer);
        Objects.requireNonNull(command);
        long started=originalStartedNanos;
        try {
            HttpUserMutationSuspension.remaining(started);
            if(suspension==null)return database.transaction(sql->{
                lock(sql);HttpUserMutationSuspension.remaining(started);var replay=replay(sql,writer,command);if(replay!=null)return replay;
                // Compatibility constructors are safe only for owners without retained
                // migration history. History is immutable and never age-evicted (V7).
                if(PostgresLiveUserSnapshot.hasHistory(sql,command.identityId()))throw new Rejected(503);
                var result=apply(sql,writer,command);HttpUserMutationSuspension.remaining(started);return result;
            });
            var prepared=database.transaction(sql->prepare(sql,writer,command,started));
            if(prepared.ack()!=null){HttpUserMutationSuspension.remaining(started);return prepared.ack();}
            HttpUserMutationSuspension.remaining(started);
            // Both transaction scope AND its pool connection have ended before I/O.
            var response=LiveUserRevision.suspensionResponse(prepared.request(),suspension.suspend(mutationWriter,prepared.request(),started));
            HttpUserMutationSuspension.remaining(started);
            return database.transaction(sql->{
                lock(sql);HttpUserMutationSuspension.remaining(started);var replay=replay(sql,writer,command);if(replay!=null)return replay;
                HttpUserMutationSuspension.remaining(started);
                var retained=journal(sql,writer,command);
                if(retained==null||!retained.equals(prepared.request()))throw new Rejected(409);
                var actual=PostgresLiveUserSnapshot.read(sql,command.identityId(),command.expectedVersion()==0);
                if(!Objects.equals(actual.baseline(),retained.get("baseline"))||!Objects.equals(actual.before(),retained.get("before")))throw new Rejected(409);
                LiveUserRevision.suspensionResponse(retained,response);
                var ack=apply(sql,writer,command);HttpUserMutationSuspension.remaining(started);return ack;
            });
        } catch (org.jooq.exception.DataAccessException e) {
            if ("23505".equals(e.sqlState())) throw new Rejected(409);
            throw new Rejected(503);
        } catch(LiveUserCurrentStore.Rejected e){throw new Rejected(e.status()==409?409:503);}
        catch(com.arman.bank.userservice.application.MigrationBaselineStore.Rejected e){throw new Rejected(e.status()==503?503:409);}
        catch(IllegalArgumentException e){throw new Rejected(503);}
        }
    private record Prepared(VerificationProjection.Ack ack,Map<String,Object> request){}
    private Prepared prepare(DSLContext sql,String writer,VerificationProjection c,long started){
        lock(sql);HttpUserMutationSuspension.remaining(started);var replay=replay(sql,writer,c);if(replay!=null)return new Prepared(replay,null);
        // An old immutable request is custody, never permission to call Auth
        // through a newly established RESERVED/PUBLISHED migration fence.
        PostgresMigrationBaselineStore.requireUnreserved(sql,c.identityId());
        var retained=journal(sql,writer,c);if(retained!=null)return new Prepared(null,retained);
        validatePreflight(sql,c);
        var state=PostgresLiveUserSnapshot.read(sql,c.identityId(),c.expectedVersion()==0);
        var request=LiveUserRevision.suspension(mutationWriter,c.operationId(),c.identityId(),state.baseline(),state.before(),LiveUserRevision.projection(c));
        byte[] original=LiveUserCurrentRoutes.encode(commandFields(c)),encoded=LiveUserCurrentRoutes.encode(request);
        if(sql.fetchOne("select count(*) from live_user_projection_commands").get(0,Long.class)>=100000)throw new Rejected(503);
        sql.execute("insert into live_user_projection_commands(writer_id,operation_id,identity_id,projection_command,suspension_request,payload_hash,created_at_us) values(?,?,?,?::jsonb,?::jsonb,?,floor(extract(epoch from clock_timestamp())*1000000)::bigint)",writer,c.operationId(),c.identityId(),new String(original,StandardCharsets.UTF_8),new String(encoded,StandardCharsets.UTF_8),request.get("payload_hash"));
        HttpUserMutationSuspension.remaining(started);return new Prepared(null,request);
    }
    private Map<String,Object> journal(DSLContext sql,String writer,VerificationProjection c){
        Record row=sql.fetchOne("select * from live_user_projection_commands where writer_id=? and operation_id=?",writer,c.operationId());if(row==null)return null;
        VerificationProjection original;
        try{original=VerificationProjectionRoutes.parse(row.get("projection_command").toString().getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new Rejected(503);}
        if(!original.equals(c)||!c.identityId().equals(row.get("identity_id",UUID.class)))throw new Rejected(409);
        var request=LiveUserRevision.suspensionRequest(mutationWriter,LiveUserCurrentRoutes.parse(row.get("suspension_request").toString().getBytes(StandardCharsets.UTF_8)));
        if(!Objects.equals(row.get("payload_hash"),request.get("payload_hash"))||!c.operationId().toString().equals(request.get("operation_id"))||!c.identityId().toString().equals(request.get("identity_id"))||!LiveUserRevision.projection(c).equals(request.get("after")))throw new Rejected(409);
        return request;
    }
    private VerificationProjection.Ack replay(DSLContext sql,String writer,VerificationProjection c){
        Record retained=sql.fetchOne("select *,expires_at>clock_timestamp() as live from verification_projection_operations where writer_id=? and operation_id=?",writer,c.operationId());
        if(retained!=null&&Boolean.TRUE.equals(retained.get("live",Boolean.class))){if(!command(retained).equals(c))throw new Rejected(409);return command(retained).ack();}
        if(sql.fetchOne("select operation_id from verification_projection_tombstones where writer_id=? and operation_id=?",writer,c.operationId())!=null)throw new Rejected(410);
        return null;
    }
    private static Map<String,Object> commandFields(VerificationProjection c){var m=new LinkedHashMap<String,Object>();m.put("operation_id",c.operationId().toString());m.put("identity_id",c.identityId().toString());m.put("expected_version",c.expectedVersion());m.put("version",c.version());m.put("canonical_phone",c.canonicalPhone());m.put("phone_contact_version",c.phoneContactVersion());m.put("verification_epoch",c.verificationEpoch());m.put("payload_hash",c.payloadHash());return m;}
    private static void validatePreflight(DSLContext sql,VerificationProjection c){
        PostgresMigrationBaselineStore.requireUnreserved(sql,c.identityId());
        Record claim=sql.fetchOne("select * from registration_phone_claims where identity_id=? for update",c.identityId());Record directory=sql.fetchOne("select * from profile_directory where identity_id=? for update",c.identityId());Record current=sql.fetchOne("select * from verification_projection_current where identity_id=? for update",c.identityId());
        if(c.expectedVersion()!=(current==null?0:current.get("version",Integer.class)))throw new Rejected(409);
        if(current!=null){boolean same=c.canonicalPhone().equals(current.get("canonical_phone",String.class));int contact=current.get("phone_contact_version",Integer.class);if(c.verificationEpoch()<=current.get("verification_epoch",Long.class)||(same?c.phoneContactVersion()!=contact:c.phoneContactVersion()<=contact))throw new Rejected(409);}
        String old=current==null?c.canonicalPhone():current.get("canonical_phone",String.class);
        if((claim!=null&&!old.equals(claim.get("phone_number")))||(current!=null&&claim==null)||(directory!=null&&directory.get("phone_number")!=null&&!old.equals(directory.get("phone_number"))))throw new Rejected(409);
        if(sql.fetchOne("select identity_id from registration_phone_claims where phone_number=? and identity_id<>?",c.canonicalPhone(),c.identityId())!=null||sql.fetchOne("select identity_id from profile_directory where phone_number=? and identity_id<>?",c.canonicalPhone(),c.identityId())!=null)throw new Rejected(409);
        if(directory!=null&&"primary".equals(directory.get("shard_id"))){
            Record physical=sql.fetchOne("select * from profiles where identity_id=? for update",c.identityId());
            if(physical==null&&Boolean.TRUE.equals(directory.get("initialized",Boolean.class)))throw new Rejected(409);
            if(physical!=null){boolean pending=!Boolean.TRUE.equals(directory.get("initialized",Boolean.class))&&physical.get("phone_number")==null&&!Boolean.TRUE.equals(physical.get("phone_verified",Boolean.class));
                if(!Objects.equals(physical.get("id"),directory.get("profile_id"))||(!pending&&(!Objects.equals(physical.get("phone_number"),directory.get("phone_number"))||!Objects.equals(physical.get("phone_verified"),directory.get("phone_verified")))))throw new Rejected(409);}
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
        PostgresMigrationBaselineStore.requireUnreserved(sql, c.identityId());
        Record claim = sql.fetchOne("select * from registration_phone_claims where identity_id=? for update", c.identityId());
        Record directory = sql.fetchOne("select * from profile_directory where identity_id=? for update", c.identityId());
        Record current = sql.fetchOne("select * from verification_projection_current where identity_id=? for update", c.identityId());
        if (c.expectedVersion() != (current == null ? 0 : current.get("version", Integer.class))) throw new Rejected(409);
        if (current != null) {
            boolean same = c.canonicalPhone().equals(current.get("canonical_phone", String.class));
            int contact = current.get("phone_contact_version", Integer.class);
            if (c.verificationEpoch() <= current.get("verification_epoch", Long.class)
                    || (same ? c.phoneContactVersion() != contact : c.phoneContactVersion() <= contact)) throw new Rejected(409);
        }
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
