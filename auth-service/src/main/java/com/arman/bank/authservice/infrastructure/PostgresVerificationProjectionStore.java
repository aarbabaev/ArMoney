package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.VerificationProjectionStore;
import com.arman.bank.authservice.domain.VerificationProjection;
import com.arman.bank.authservice.domain.VerificationProjection.*;
import com.arman.bank.authservice.domain.VerificationProjection.Error;
import com.arman.bank.runtime.Database;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import java.util.function.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Durable auth commands only; never updates identity/receipt/epoch/session/notice authority. */
public final class PostgresVerificationProjectionStore implements VerificationProjectionStore {
    private final Database database;
    private final Policy policy;
    private final byte[] retentionKey, keyFingerprint;
    private final Function<DSLContext, AuthorityPort> authority;
    private static final Object LOCK_CONTEXT = new Object();
    private record Locked(DSLContext context,long transaction) {}
    /** Global prefix for every projection/verification writer; marker is scoped to this DB transaction. */
    public static void lockControls(DSLContext sql) {
        if(sql.fetchOne("select id from verification_control where id=1 for update")==null
                ||sql.fetchOne("select id from auth_verification_projection_control where id=1 for update")==null
                ||sql.fetchOne("select id from sso_login_proof_control where id=1 for update")==null)throw new Failure(Error.UNAVAILABLE);
        sql.configuration().data(LOCK_CONTEXT,new Locked(sql,sql.fetchOne("select txid_current() as id").get("id",Long.class)));
    }
    public static void requireLocked(DSLContext sql) {
        Object marker=sql.configuration().data(LOCK_CONTEXT);
        if(!(marker instanceof Locked locked)||locked.context()!=sql||locked.transaction()!=sql.fetchOne("select txid_current() as id").get("id",Long.class))throw new Failure(Error.UNAVAILABLE);
    }
    public PostgresVerificationProjectionStore(Database database, Policy policy, byte[] retentionKey, Function<DSLContext, AuthorityPort> authority) {
        this.database = Objects.requireNonNull(database); this.policy = Objects.requireNonNull(policy); this.authority = Objects.requireNonNull(authority);
        if (retentionKey == null || retentionKey.length != 32) throw new IllegalArgumentException("Invalid projection retention key");
        this.retentionKey = retentionKey.clone(); keyFingerprint = hash(this.retentionKey);
    }
    private <T> T transaction(BiFunction<DSLContext, Instant, T> action) {
        return database.transaction(sql -> {
            lockControls(sql);
            var control = sql.fetchOne("select retention_key_fingerprint from auth_verification_projection_control where id=1 for update");
            if (control == null) throw new Failure(Error.UNAVAILABLE);
            byte[] stored = control.get("retention_key_fingerprint", byte[].class);
            if (stored != null && !MessageDigest.isEqual(stored, keyFingerprint)) throw new Failure(Error.UNAVAILABLE);
            return action.apply(sql, now(sql));
        });
    }
    private void admit(String writer) {
        VerificationProjection.writer(writer, policy);
        boolean permit = transaction((sql, now) -> {
            byte[] scope = writerHash(writer);
            var row = sql.fetchOne("select credits,refilled_at from auth_verification_projection_admission where writer_hash=?", scope);
            BigDecimal credits;
            Instant refilled = now;
            if (row == null) {
                if (count(sql, "select count(*) as n from auth_verification_projection_admission") >= 16) return false;
                credits = BigDecimal.valueOf(20);
                sql.execute("insert into auth_verification_projection_admission(writer_hash,credits,refilled_at) values (?,?,cast(? as timestamptz))", scope, credits, at(now));
            } else {
                Instant previous = time(row, "refilled_at");
                if (previous.isAfter(now)) refilled = previous;
                long nanos = now.isAfter(previous) ? Math.min(Duration.between(previous, now).toMillis(), 10_000) * 1_000_000 : 0;
                credits = row.get("credits", BigDecimal.class).add(BigDecimal.valueOf(nanos, 9).multiply(BigDecimal.valueOf(2))).min(BigDecimal.valueOf(20));
            }
            boolean allowed = credits.compareTo(BigDecimal.ONE) >= 0;
            sql.execute("update auth_verification_projection_admission set credits=?,refilled_at=cast(? as timestamptz) where writer_hash=?",
                allowed ? credits.subtract(BigDecimal.ONE) : credits, at(refilled), scope);
            return allowed;
        });
        if (!permit) throw new Failure(Error.RATE_LIMITED);
    }
    @Override public Status allocate(String writer, Command command) {
        admit(writer); VerificationProjection.validate(command);
        return transaction((sql, now) -> allocateInTransaction(sql,writer,command));
    }
    public Status allocateInTransaction(DSLContext sql,String writer,Command command) {
            requireLocked(sql);VerificationProjection.writer(writer,policy);VerificationProjection.validate(command);
            Instant now=now(sql);
            var control=sql.fetchOne("select retention_key_fingerprint from auth_verification_projection_control where id=1");
            byte[] fingerprint=control.get("retention_key_fingerprint",byte[].class);
            if(fingerprint!=null&&!MessageDigest.isEqual(fingerprint,keyFingerprint))throw new Failure(Error.UNAVAILABLE);
            byte[] scope = writerHash(writer), identity = identityDigest(command.identityId());
            var previous = tombstone(sql, scope, command.operationId());
            if (previous != null) {
                if (!now.isBefore(time(previous, "expires_at"))) throw new Failure(Error.EXPIRED);
                if (!command.payloadHash().equals(previous.get("payload_hash", String.class))) throw new Failure(Error.CONFLICT);
                var full = full(sql, scope, command.operationId());
                if (full == null) throw new Failure(Error.UNAVAILABLE);
                return status(full, previous);
            }
            Authority current = current(sql, command, now);
            if (count(sql, "select count(*) as n from auth_verification_projection_commands") >= policy.maxCommands()
                    || count(sql, "select count(*) as n from auth_verification_projection_tombstones") >= policy.maxTombstones()) throw new Failure(Error.RATE_LIMITED);
            if (sql.fetchOne("select operation_id from auth_verification_projection_tombstones where identity_digest=? and recovery_barrier", identity) != null) throw new Failure(Error.CONFLICT);
            var latest = sql.fetchOne("select version,phone_contact_version,verification_epoch from auth_verification_projection_tombstones where identity_digest=? order by version desc limit 1", identity);
            if ((latest == null && command.expectedVersion() != 0) || (latest != null && (command.expectedVersion() != latest.get("version", Integer.class)
                    || command.verificationEpoch() <= latest.get("verification_epoch", Long.class)))) throw new Failure(Error.CONFLICT);
            sql.execute("update auth_verification_projection_control set retention_key_fingerprint=? where id=1 and retention_key_fingerprint is null", keyFingerprint);
            sql.execute("""
                insert into auth_verification_projection_tombstones(writer_hash,operation_id,payload_hash,identity_digest,version,
                    phone_contact_version,verification_epoch,state,recovery_barrier,created_at,expires_at)
                values (?,?,?,?,?,?,?,'PENDING',true,cast(? as timestamptz),cast(? as timestamptz))
                """, scope, command.operationId(), command.payloadHash(), identity, command.version(), command.phoneContactVersion(), command.verificationEpoch(), at(now), at(now.plusSeconds(604800)));
            sql.execute("""
                insert into auth_verification_projection_commands(writer_hash,operation_id,identity_id,enrollment_id,expected_version,version,
                    canonical_phone,phone_contact_version,email_contact_version,verification_epoch,email_receipt_id,phone_receipt_id,
                    payload_hash,request_body,available_at,created_at,expires_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz))
                """, scope, command.operationId(), command.identityId(), current.enrollmentId(), command.expectedVersion(), command.version(), command.canonicalPhone(),
                command.phoneContactVersion(), current.emailContactVersion(), command.verificationEpoch(), current.emailReceiptId(), current.phoneReceiptId(),
                command.payloadHash(), HttpVerificationProjection.body(command), at(now), at(now), at(now.plusSeconds(604800)));
            return new Status(command.operationId(), State.PENDING, null, now.plusSeconds(604800));
    }
    @Override public Status status(String writer, UUID operation) {
        admit(writer);
        if (operation == null) throw new Failure(Error.BAD_INPUT);
        return transaction((sql, now) -> {
            var tomb = tombstone(sql, writerHash(writer), operation);
            if (tomb == null) throw new Failure(Error.CONFLICT);
            if (!now.isBefore(time(tomb, "expires_at"))) throw new Failure(Error.EXPIRED);
            var full = full(sql, writerHash(writer), operation);
            if (full == null) throw new Failure(Error.UNAVAILABLE);
            return status(full, tomb);
        });
    }
    public Status statusInTransaction(DSLContext sql,String writer,UUID operation) {
        requireLocked(sql); VerificationProjection.writer(writer,policy);
        var tomb=tombstone(sql,writerHash(writer),operation);
        if(tomb==null)throw new Failure(Error.CONFLICT);
        if(!now(sql).isBefore(time(tomb,"expires_at")))throw new Failure(Error.EXPIRED);
        var full=full(sql,writerHash(writer),operation);
        if(full==null)throw new Failure(Error.UNAVAILABLE);
        return status(full,tomb);
    }
    @Override public Optional<Claim> claim(String writer) {
        VerificationProjection.writer(writer, policy);
        return transaction((sql, now) -> {
            // Drain overdue full payloads before new sends. cleanup is separately scheduled in bounded batches.
            if (sql.fetchOne("select operation_id from auth_verification_projection_commands where expires_at<=cast(? as timestamptz) limit 1", at(now)) != null) return Optional.empty();
            var row = sql.fetchOne("""
                select c.* from auth_verification_projection_commands c join auth_verification_projection_tombstones t using(writer_hash,operation_id)
                where c.writer_hash=? and t.state='PENDING' and c.expires_at>cast(? as timestamptz)
                    and c.available_at<=cast(? as timestamptz) and (c.fence is null or c.lease_until<=cast(? as timestamptz))
                order by c.available_at,c.operation_id limit 1 for update of c
                """, writerHash(writer), at(now), at(now), at(now));
            if (row == null) return Optional.empty();
            UUID fence = UUID.randomUUID();
            sql.execute("update auth_verification_projection_commands set fence=?,lease_until=cast(? as timestamptz),attempts=attempts+1 where writer_hash=? and operation_id=?",
                fence, at(now.plusSeconds(30)), writerHash(writer), row.get("operation_id", UUID.class));
            return Optional.of(new Claim(writer, command(row), row.get("request_body", byte[].class), fence, now.plusSeconds(30), time(row,"expires_at"), row.get("attempts",Integer.class)+1));
        });
    }
    @Override public boolean finish(Claim claim, Result result) {
        if (claim == null || result == null) throw new Failure(Error.BAD_INPUT);
        VerificationProjection.writer(claim.writer(), policy); VerificationProjection.validate(claim.command());
        return transaction((sql, now) -> finishInTransaction(sql,claim,result));
    }
    public boolean finishInTransaction(DSLContext sql,Claim claim,Result result) {
            requireLocked(sql);if(claim==null||result==null)throw new Failure(Error.BAD_INPUT);
            VerificationProjection.writer(claim.writer(),policy);VerificationProjection.validate(claim.command());
            Instant now=now(sql);
            var control=sql.fetchOne("select retention_key_fingerprint from auth_verification_projection_control where id=1");
            byte[] fingerprint=control.get("retention_key_fingerprint",byte[].class);
            if(fingerprint!=null&&!MessageDigest.isEqual(fingerprint,keyFingerprint))throw new Failure(Error.UNAVAILABLE);
            byte[] writer = writerHash(claim.writer()); UUID operation = claim.command().operationId();
            var row = full(sql, writer, operation); var tomb = tombstone(sql, writer, operation);
            if (row == null || tomb == null || !"PENDING".equals(tomb.get("state")) || !Objects.equals(claim.fence(),row.get("fence",UUID.class))
                    || row.get("lease_until") == null || !now.isBefore(time(row,"lease_until"))) return false;
            if (!now.isBefore(time(row,"expires_at"))) { transition(sql,writer,operation,State.EXPIRED_UNRESOLVED,true); return false; }
            if (!command(row).equals(claim.command()) || !Arrays.equals(row.get("request_body",byte[].class),claim.requestBody())) throw new Failure(Error.CONFLICT);
            State state;
            switch (result.outcome()) {
                case RETRY -> { retry(sql,writer,operation,row.get("attempts",Integer.class),now); return true; }
                case RATE_LIMITED -> { retry(sql,writer,operation,Math.max(5,row.get("attempts",Integer.class)),now); return true; }
                case BAD_REQUEST -> state=State.DISABLED_BAD_REQUEST;
                case UNAUTHORIZED -> state=State.DISABLED_UNAUTHORIZED;
                case CONFLICT -> state=State.CONFLICT;
                case EXPIRED -> state=State.EXPIRED_REPLAY;
                case ACK -> {
                    if (!result.acknowledgement().matches(claim.command())) { retry(sql,writer,operation,row.get("attempts",Integer.class),now); return true; }
                    Authority active;
                    try { active=current(sql,claim.command(),now); }
                    catch (Failure failure) {
                        if (failure.error()==Error.UNAVAILABLE) { retry(sql,writer,operation,row.get("attempts",Integer.class),now); return true; }
                        if (failure.error()!=Error.CONFLICT) throw failure;
                        transition(sql,writer,operation,State.SUPERSEDED,false); return true;
                    }
                    if (!active.enrollmentId().equals(row.get("enrollment_id",UUID.class)) || active.emailContactVersion()!=row.get("email_contact_version",Integer.class)
                            || !active.emailReceiptId().equals(row.get("email_receipt_id",UUID.class)) || !active.phoneReceiptId().equals(row.get("phone_receipt_id",UUID.class))) state=State.SUPERSEDED;
                    else state=State.ACKNOWLEDGED;
                }
                default -> throw new Failure(Error.UNAVAILABLE);
            }
            transition(sql,writer,operation,state,state!=State.ACKNOWLEDGED && state!=State.SUPERSEDED);
            return true;
    }
    private Authority current(DSLContext sql, Command command, Instant now) {
        Authority current;
        try {
            AuthorityPort port=authority.apply(sql);
            if(port==null) throw new Failure(Error.UNAVAILABLE);
            current=port.current(command.identityId(),now);
        } catch(Failure failure) { throw failure; }
        catch(RuntimeException unavailable) { throw new Failure(Error.UNAVAILABLE); }
        if(current==null) throw new Failure(Error.UNAVAILABLE);
        if(!current.matches(command)) throw new Failure(Error.CONFLICT);
        if(sql.fetchOne("select id from identities where id=? for update",command.identityId())==null) throw new Failure(Error.CONFLICT);
        var enrollment=sql.fetchOne("select phone,state from verification_enrollments where id=? for update",current.enrollmentId());
        if(enrollment==null || !command.canonicalPhone().equals(enrollment.get("phone")) || !"VERIFIED".equals(enrollment.get("state"))) throw new Failure(Error.CONFLICT);
        if(sql.fetch("""
                select id from verification_receipts where enrollment_id=? and ((id=? and purpose='REGISTRATION_EMAIL' and contact_version=?)
                    or (id=? and purpose='REGISTRATION_PHONE' and contact_version=?)) for update
                """,current.enrollmentId(),current.emailReceiptId(),current.emailContactVersion(),current.phoneReceiptId(),current.phoneContactVersion()).size()!=2)
            throw new Failure(Error.CONFLICT);
        return current;
    }
    private static void retry(DSLContext sql,byte[] writer,UUID operation,int attempts,Instant now) {
        long delay=Math.min(60,5L<<Math.min(4,Math.max(0,attempts-1)));
        sql.execute("update auth_verification_projection_commands set fence=null,lease_until=null,available_at=cast(? as timestamptz) where writer_hash=? and operation_id=?",at(now.plusSeconds(delay)),writer,operation);
    }
    private static void transition(DSLContext sql,byte[] writer,UUID operation,State state,boolean barrier) {
        sql.execute("update auth_verification_projection_tombstones set state=?,recovery_barrier=? where writer_hash=? and operation_id=?",state.name(),barrier,writer,operation);
        sql.execute("update auth_verification_projection_commands set fence=null,lease_until=null where writer_hash=? and operation_id=?",writer,operation);
    }
    @Override public void cleanup() {
        transaction((sql,now)->{
            var expired=sql.fetch("select writer_hash,operation_id from auth_verification_projection_commands where expires_at<=cast(? as timestamptz) order by expires_at,operation_id limit 100",at(now));
            for(var row:expired) {
                byte[] writer=row.get("writer_hash",byte[].class); UUID operation=row.get("operation_id",UUID.class);
                var tomb=tombstone(sql,writer,operation);
                if("PENDING".equals(tomb.get("state"))) transition(sql,writer,operation,State.EXPIRED_UNRESOLVED,true);
                sql.execute("delete from auth_verification_projection_commands where writer_hash=? and operation_id=?",writer,operation);
            }
            return null;
        });
    }
    private static Record tombstone(DSLContext sql,byte[] writer,UUID operation) { return sql.fetchOne("select * from auth_verification_projection_tombstones where writer_hash=? and operation_id=?",writer,operation); }
    private static Record full(DSLContext sql,byte[] writer,UUID operation) { return sql.fetchOne("select * from auth_verification_projection_commands where writer_hash=? and operation_id=?",writer,operation); }
    private static Status status(Record full,Record tomb) {
        var state=State.valueOf(tomb.get("state",String.class)); var command=command(full);
        Ack ack=(state==State.ACKNOWLEDGED || state==State.SUPERSEDED)?new Ack(command.operationId(),command.identityId(),command.version(),command.phoneContactVersion(),command.verificationEpoch(),command.payloadHash()):null;
        return new Status(command.operationId(),state,ack,time(full,"expires_at"));
    }
    private static Command command(Record row) { return new Command(row.get("operation_id",UUID.class),row.get("identity_id",UUID.class),row.get("expected_version",Integer.class),row.get("version",Integer.class),row.get("canonical_phone",String.class),row.get("phone_contact_version",Integer.class),row.get("verification_epoch",Long.class),row.get("payload_hash",String.class)); }
    private byte[] identityDigest(UUID identity) {
        try {
            var bytes=new ByteArrayOutputStream(); var output=new DataOutputStream(bytes);
            for(String field:new String[]{"armoney-auth-projection-identity-v1",identity.toString()}) { byte[] value=field.getBytes(StandardCharsets.UTF_8); output.writeInt(value.length); output.write(value); }
            var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(retentionKey,"HmacSHA256")); return mac.doFinal(bytes.toByteArray());
        } catch(IOException|GeneralSecurityException unavailable) { throw new Failure(Error.UNAVAILABLE); }
    }
    private static byte[] writerHash(String writer) { return hash(("armoney-auth-projection-writer-v1:"+writer).getBytes(StandardCharsets.UTF_8)); }
    private static byte[] hash(byte[] bytes) { try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(NoSuchAlgorithmException unavailable){throw new Failure(Error.UNAVAILABLE);} }
    private static OffsetDateTime at(Instant time) { return time.atOffset(ZoneOffset.UTC); }
    private static Instant time(Record row,String field) { return row.get(field,OffsetDateTime.class).toInstant(); }
    private static Instant now(DSLContext sql) { return time(sql.fetchOne("select clock_timestamp() as now"),"now"); }
    private static long count(DSLContext sql,String query,Object...args) { return sql.fetchOne(query,args).get("n",Long.class); }
}
