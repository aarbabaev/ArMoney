package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.LoginProofStore;
import com.arman.bank.authservice.domain.LoginProof;
import com.arman.bank.authservice.domain.LoginProof.*;
import com.arman.bank.authservice.domain.LoginProof.Error;
import com.arman.bank.runtime.Database;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Owning-database immutable snapshots and budgets. No network, banking session or notice dispatch. */
public final class PostgresLoginProofStore implements LoginProofStore {
    private final Database database;
    private final Policy policy;
    private final byte[] retentionKey;
    private final Function<DSLContext, EligibilityPort> eligibility;
    public PostgresLoginProofStore(Database database, Policy policy, byte[] retentionKey,
            Function<DSLContext, EligibilityPort> eligibility) {
        this.database = Objects.requireNonNull(database); this.policy = Objects.requireNonNull(policy);
        if (retentionKey == null || retentionKey.length != 32) throw new IllegalArgumentException("Invalid proof retention key");
        this.retentionKey = retentionKey.clone(); this.eligibility = Objects.requireNonNull(eligibility);
    }
    private <T> T transaction(BiFunction<DSLContext, Instant, T> action) {
        return database.transaction(sql -> {
            try { PostgresVerificationProjectionStore.lockControls(sql); }
            catch (com.arman.bank.authservice.domain.VerificationProjection.Failure unavailable) { throw new Failure(Error.UNAVAILABLE); }
            return action.apply(sql, now(sql));
        });
    }
    @Override public <T>T inRequest(String writer,Function<LoginProofStore.Request,T> operation) {
        LoginProof.writer(writer,policy);
        return new PostgresLoginRequestScope(database).inRequest(writer,owned -> operation.apply(new Scoped(owned)));
    }
    /** Compatibility admission is a complete operation; callers must use inRequest to avoid a second debit. */
    @Override public void admit(String writer) { inRequest(writer,request -> null); }
    private record Admitted(PostgresLoginProofStore owner,PostgresLoginRequestScope.Request request,String writer,Binding binding,Instant startedAt,byte[] payload) implements Admission {
        private Admitted { payload=payload.clone(); }
        @Override public String toString(){return "LoginProofAdmission[redacted]";}
    }
    private final class Scoped implements LoginProofStore.Request {
        private final PostgresLoginRequestScope.Request request;
        private boolean consumed, anchored;
        private Scoped(PostgresLoginRequestScope.Request request){this.request=request;}
        private void consume(){request.check();if(consumed)throw new Failure(Error.UNAVAILABLE);consumed=true;}
        @Override public Admission admitCreate(Write input){
            request.check(); LoginProof.validate(input,request.actor(),policy);
            if(consumed||anchored)throw new Failure(Error.UNAVAILABLE);anchored=true;
            return request.transaction(sql->new Admitted(PostgresLoginProofStore.this,request,request.actor(),input.binding(),now(sql),LoginProof.payloadHash(input)));
        }
        @Override public Snapshot writeObserved(Write input,Admission admission,Observation observation){
            consume(); LoginProof.validate(input,request.actor(),policy);
            if(input.kind()!=Kind.INTERACTIVE)throw new Failure(Error.CONFLICT);
            if(!(admission instanceof Admitted value)||value.owner()!=PostgresLoginProofStore.this||value.request()!=request
                ||!request.actor().equals(value.writer())||!input.binding().equals(value.binding())
                ||!MessageDigest.isEqual(value.payload(),LoginProof.payloadHash(input)))throw new Failure(Error.UNAVAILABLE);
            return writeInternal(request,input,admission,observation);
        }
        @Override public Snapshot write(Write input){consume();if(anchored)throw new Failure(Error.UNAVAILABLE);return writeInternal(request,input,null,null);}
        @Override public Snapshot resolve(Resolve input){consume();if(anchored)throw new Failure(Error.UNAVAILABLE);return resolveInternal(request,input);}
    }
    /** An admission cannot escape its operation; staged callers must use the request capability. */
    @Override public Admission admitCreate(String writer,Write input){throw new Failure(Error.UNAVAILABLE);}
    @Override public Snapshot writeObserved(String writer,Write input,Admission admission,Observation observation){throw new Failure(Error.UNAVAILABLE);}
    @Override public Snapshot write(String writer,Write input){return inRequest(writer,request->request.write(input));}
    private Snapshot writeInternal(PostgresLoginRequestScope.Request request,Write input,Admission admission,Observation observation){
        request.check();
        String writer=request.actor();
        LoginProof.validate(input, writer, policy);
        return request.transaction(sql -> {
            Instant now=now(sql);
            LoginProof.unexpired(input.expiresAt(), now);
            byte[] requester = LoginProof.requesterHash(writer, input.issuer(), input.clientId()), payload = LoginProof.payloadHash(input);
            var command = sql.fetchOne("select * from sso_login_proof_commands where requester_hash = ? and command_id = ?", requester, input.commandId());
            if (command != null) {
                same(command.get("payload_hash", byte[].class), payload);
                LoginProof.unexpired(time(command, "retained_until"), now);
                var original = sql.fetchOne("select * from sso_login_proofs where id = ?", command.get("proof_id", UUID.class));
                if (original == null) throw new Failure(Error.UNAVAILABLE);
                LoginProof.unexpired(time(original, "expires_at"), now);
                Authority active = authority(sql, input.binding(), now,admission,observation);
                if (input.kind() == Kind.INTERACTIVE) event(sql, input, active, now, false);
                finalCheck(sql, input, active,admission,observation);
                return snapshot(original);
            }
            var code = sql.fetchOne("select * from sso_login_proofs where issuer = ? and code_digest = ?", input.issuer(), input.codeDigest());
            if (code != null) {
                same(code.get("payload_hash", byte[].class), payload);
                LoginProof.unexpired(time(code, "expires_at"), now);
                Authority active = authority(sql, input.binding(), now,admission,observation);
                if (input.kind() == Kind.INTERACTIVE) event(sql, input, active, now, false);
                reserveCommand(sql, requester, input.commandId(), payload, code.get("id", UUID.class), now);
                finalCheck(sql, input, active,admission,observation);
                return snapshot(code);
            }
            LoginProof.fresh(input, now);
            if (count(sql, "select count(*) as n from sso_login_proofs") >= policy.maxProofs()
                    || count(sql, "select count(*) as n from sso_login_proof_commands") >= policy.maxCommands())
                throw new Failure(Error.RATE_LIMITED);
            Authority active = authority(sql, input.binding(), now,admission,observation);
            if (input.kind() == Kind.INTERACTIVE) event(sql, input, active, now, true);
            UUID proof = UUID.randomUUID();
            PostgresLoginCreationBudget.reserve(sql, proof, "PROOF", now, policy.maxHourly());
            sql.execute("""
                insert into sso_login_proofs(id, issuer, code_digest, code_key_id, binding_hash, payload_hash,
                    kind, event_id, login_notice_eligible, issued_at, expires_at, created_at, retained_until)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as timestamptz), cast(? as timestamptz), cast(? as timestamptz), cast(? as timestamptz))
                """, proof, input.issuer(), input.codeDigest(), input.codeKeyId(), LoginProof.bindingHash(input.binding()), payload,
                input.kind().wire(), input.eventId(), input.kind() == Kind.INTERACTIVE, at(input.issuedAt()), at(input.expiresAt()), at(now), at(now.plusSeconds(604800)));
            reserveCommand(sql, requester, input.commandId(), payload, proof, now);
            finalCheck(sql, input, active,admission,observation);
            return new Snapshot(proof, input.codeKeyId(), input.eventId(), input.kind(), input.kind() == Kind.INTERACTIVE, input.issuedAt(), input.expiresAt());
        });
    }
    boolean owns(Database candidate){return database==candidate;}
    /** Caller already owns catalog/local evidence/intent locks. No transaction, HTTP, fresh-CREATE
     * cutoff or hourly reservation here: C2 consumes the ORIGINAL intent's charged admission. */
    Snapshot confirmInTransaction(DSLContext sql,String writer,Write input,UUID committedProof,Supplier<CurrentEvidence> evidence){
        PostgresVerificationProjectionStore.requireLocked(sql);LoginProof.validate(input,writer,policy);
        if(input.kind()!=Kind.INTERACTIVE)throw new Failure(Error.CONFLICT);
        CurrentEvidence current=evidence.get();
        if(!current.completeFor(input.binding(),now(sql)))throw new Failure(Error.CONFLICT);
        var active=new Authority(null,current);
        byte[] requester=LoginProof.requesterHash(writer,input.issuer(),input.clientId()),payload=LoginProof.payloadHash(input);
        var original=sql.fetchOne("select * from sso_login_proofs where issuer=? and code_digest=?",input.issuer(),input.codeDigest());
        var command=sql.fetchOne("select * from sso_login_proof_commands where requester_hash=? and command_id=?",requester,input.commandId());
        Snapshot result;
        if(committedProof!=null){
            if(original==null||command==null||!committedProof.equals(original.get("id",UUID.class))||!committedProof.equals(command.get("proof_id",UUID.class)))throw new Failure(Error.UNAVAILABLE);
            same(original.get("payload_hash",byte[].class),payload);same(command.get("payload_hash",byte[].class),payload);
            LoginProof.unexpired(time(original,"expires_at"),now(sql));LoginProof.unexpired(time(command,"retained_until"),now(sql));
            event(sql,input,active,now(sql),false);result=snapshot(original);
        }else{
            if(original!=null||command!=null)throw new Failure(Error.CONFLICT);
            if(count(sql,"select count(*) as n from sso_login_proofs")>=policy.maxProofs()
                ||count(sql,"select count(*) as n from sso_login_proof_commands")>=policy.maxCommands())throw new Failure(Error.RATE_LIMITED);
            Instant now=now(sql);LoginProof.unexpired(input.expiresAt(),now);event(sql,input,active,now,true);
            UUID proof=UUID.randomUUID();
            sql.execute("""
                insert into sso_login_proofs(id,issuer,code_digest,code_key_id,binding_hash,payload_hash,kind,event_id,login_notice_eligible,issued_at,expires_at,created_at,retained_until)
                values(?,?,?,?,?,?, 'interactive',?,true,cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz))
                """,proof,input.issuer(),input.codeDigest(),input.codeKeyId(),LoginProof.bindingHash(input.binding()),payload,input.eventId(),at(input.issuedAt()),at(input.expiresAt()),at(now),at(now.plusSeconds(604800)));
            reserveCommand(sql,requester,input.commandId(),payload,proof,now);
            result=new Snapshot(proof,input.codeKeyId(),input.eventId(),Kind.INTERACTIVE,true,input.issuedAt(),input.expiresAt());
        }
        CurrentEvidence checked=evidence.get();
        if(!current.equals(checked)||!checked.completeFor(input.binding(),now(sql)))throw new Failure(Error.CONFLICT);
        LoginProof.unexpired(input.expiresAt(),now(sql));return result;
    }
    private void event(DSLContext sql, Write input, Authority active, Instant now, boolean mayCreate) {
        var event = sql.fetchOne("select * from sso_login_events where issuer = ? and event_id = ?", input.issuer(), input.eventId());
        if (event != null) {
            same(event.get("binding_hash", byte[].class), LoginProof.bindingHash(input.binding()));
            same(event.get("authentication_hash", byte[].class), LoginProof.authenticationHash(input.authenticationSessionId()));
            same(event.get("identity_digest", byte[].class), LoginProof.identityHash(active.identityId()));
            same(event.get("recipient_digest", byte[].class), recipientDigest(retentionKey, active.verifiedEmail()));
            if (!now.isBefore(time(event, "recipient_expires_at")) || event.get("verified_email", String.class) == null
                    || !time(event, "password_authenticated_at").equals(input.passwordAuthenticatedAt())
                    || !Objects.equals(active.emailContactVersion(),event.get("email_contact_version", Integer.class))
                    || !Objects.equals(active.phoneContactVersion(),event.get("phone_contact_version", Integer.class))
                    || active.verificationEpoch() != event.get("verification_epoch", Long.class)
                    || !Objects.equals(active.projectionRevision(),event.get("projection_revision", Integer.class))) throw new Failure(Error.CONFLICT);
            requireEventSource(event,active);return;
        }
        if (!mayCreate) throw new Failure(Error.UNAVAILABLE);
        if (count(sql, "select count(*) as n from sso_login_events") >= policy.maxProofs()) throw new Failure(Error.RATE_LIMITED);
        if(active.isMigration()){insertMigrationEvent(sql,input,active,now);return;}
        sql.execute("""
            insert into sso_login_events(issuer, event_id, binding_hash, authentication_hash, identity_id, identity_digest,
                password_authenticated_at, verified_email, recipient_digest, email_contact_version, phone_contact_version,
                verification_epoch, projection_revision, recipient_expires_at, created_at, retained_until)
            values (?, ?, ?, ?, ?, ?, cast(? as timestamptz), ?, ?, ?, ?, ?, ?, cast(? as timestamptz), cast(? as timestamptz), cast(? as timestamptz))
            """, input.issuer(), input.eventId(), LoginProof.bindingHash(input.binding()), LoginProof.authenticationHash(input.authenticationSessionId()),
            active.identityId(), LoginProof.identityHash(active.identityId()), at(input.passwordAuthenticatedAt()), active.verifiedEmail(),
            recipientDigest(retentionKey, active.verifiedEmail()), active.emailContactVersion(), active.phoneContactVersion(),
            active.verificationEpoch(), active.projectionRevision(), at(now.plusSeconds(86400)), at(now), at(now.plusSeconds(604800)));
    }
    private static void requireEventSource(Record event,Authority active){
        if(!active.isMigration()){if(!"REGISTRATION_DUAL_CONTACT".equals(event.get("eligibility_source")))throw new Failure(Error.CONFLICT);return;}
        var grant=active.current().migrationGrant();
        if(!SourceEligibilitySql.MIGRATION.equals(event.get("eligibility_source"))||!grant.decisionId().equals(event.get("eligibility_source_id"))||!grant.decisionId().equals(event.get("migration_decision_id"))
            ||!Long.valueOf(grant.classificationGeneration()).equals(event.get("classification_generation"))||!Long.valueOf(grant.sourceRevision()).equals(event.get("source_revision"))
            ||!Long.valueOf(grant.emailReceiptGeneration()).equals(event.get("migration_email_receipt_generation"))||!Long.valueOf(grant.phoneReceiptGeneration()).equals(event.get("migration_phone_receipt_generation"))
            ||!Objects.equals(grant.verifiedContactGeneration(),event.get("migration_verified_contact_generation")))throw new Failure(Error.CONFLICT);
        same(event.get("source_stamp",byte[].class),HexFormat.of().parseHex(grant.authorityStamp()));
    }
    private void insertMigrationEvent(DSLContext sql,Write input,Authority active,Instant now){
        var grant=active.current().migrationGrant();
        sql.execute("""
            insert into sso_login_events(issuer,event_id,binding_hash,authentication_hash,identity_id,identity_digest,password_authenticated_at,verified_email,recipient_digest,
              email_contact_version,phone_contact_version,verification_epoch,projection_revision,recipient_expires_at,created_at,retained_until,
              eligibility_source,eligibility_source_id,classification_generation,source_revision,source_stamp,migration_decision_id,migration_email_receipt_generation,migration_phone_receipt_generation,migration_verified_contact_generation)
            values(?,?,?,?,?,?,cast(? as timestamptz),?,?,null,null,?,null,cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),?,?,?,?,?,?,?,?,?)
            """,input.issuer(),input.eventId(),LoginProof.bindingHash(input.binding()),LoginProof.authenticationHash(input.authenticationSessionId()),active.identityId(),LoginProof.identityHash(active.identityId()),at(input.passwordAuthenticatedAt()),active.verifiedEmail(),recipientDigest(retentionKey,active.verifiedEmail()),grant.verificationEpoch(),at(now.plusSeconds(86400)),at(now),at(now.plusSeconds(604800)),
            SourceEligibilitySql.MIGRATION,grant.decisionId(),grant.classificationGeneration(),grant.sourceRevision(),HexFormat.of().parseHex(grant.authorityStamp()),grant.decisionId(),grant.emailReceiptGeneration(),grant.phoneReceiptGeneration(),grant.verifiedContactGeneration());
    }
    private void reserveCommand(DSLContext sql, byte[] requester, UUID command, byte[] payload, UUID proof, Instant now) {
        if (count(sql, "select count(*) as n from sso_login_proof_commands") >= policy.maxCommands()) throw new Failure(Error.RATE_LIMITED);
        sql.execute("""
            insert into sso_login_proof_commands(requester_hash, command_id, payload_hash, proof_id, created_at, retained_until)
            values (?, ?, ?, ?, cast(? as timestamptz), cast(? as timestamptz))
            """, requester, command, payload, proof, at(now), at(now.plusSeconds(604800)));
    }
    private record Authority(Activation legacy,CurrentEvidence current) {
        boolean isMigration(){return current!=null&&current.migrationGrant()!=null;}
        UUID identityId(){return current==null?legacy.identityId():isMigration()?current.migrationGrant().identityId():current.grant().identityId();}
        Integer emailContactVersion(){return isMigration()?null:current==null?legacy.emailContactVersion():current.grant().emailContactVersion();}
        Integer phoneContactVersion(){return isMigration()?null:current==null?legacy.phoneContactVersion():current.grant().phoneContactVersion();}
        long verificationEpoch(){return current==null?legacy.verificationEpoch():isMigration()?current.migrationGrant().verificationEpoch():current.grant().verificationEpoch();}
        Integer projectionRevision(){return isMigration()?null:current==null?legacy.projectionRevision():current.grant().projectionRevision();}
        String verifiedEmail(){return current==null?legacy.verifiedEmail():current.verifiedEmail();}
        boolean completeFor(Binding binding,Instant now){return current==null?legacy.completeFor(binding,now):current.completeFor(binding,now);}
    }
    private Authority authority(DSLContext sql,Binding binding,Instant now,Admission admission,Observation observation){
        if(admission==null)return new Authority(active(sql,binding,now),null);
        try{
            var current=new PostgresBankingEligibilityStore(sql).requireForProof(binding,observation,admission.startedAt());
            return new Authority(null,current);
        }catch(com.arman.bank.authservice.domain.Verification.Failure rejected){
            throw new Failure(rejected.error()==com.arman.bank.authservice.domain.Verification.Error.CONFLICT?Error.CONFLICT:Error.UNAVAILABLE);
        }
    }
    private Activation active(DSLContext sql, Binding binding, Instant now) {
        Activation active;
        try {
            EligibilityPort gate = eligibility.apply(sql);
            if (gate == null) throw new Failure(Error.UNAVAILABLE);
            active = gate.requireActive(binding, now);
        } catch (Failure failure) { throw failure; }
        catch (RuntimeException unavailable) { throw new Failure(Error.UNAVAILABLE); }
        if (active == null) throw new Failure(Error.UNAVAILABLE);
        if (!active.completeFor(binding, now)) throw new Failure(Error.CONFLICT);
        if (sql.fetchOne("""
                select i.id from identities i join external_identities e on e.identity_id = i.id
                where i.id = ? and e.issuer = ? and e.subject = ? for update of i, e
                """, active.identityId(), binding.issuer(), binding.subject()) == null) throw new Failure(Error.CONFLICT);
        return active;
    }
    private void finalCheck(DSLContext sql,Write input,Authority active,Admission admission,Observation observation){
        if(admission!=null){
            var finalAuthority=authority(sql,input.binding(),now(sql),admission,observation);
            if(!active.current().equals(finalAuthority.current()))throw new Failure(Error.CONFLICT);
        }
        Instant checked=now(sql);
        LoginProof.unexpired(input.expiresAt(),checked);
        if(!active.completeFor(input.binding(),checked))throw new Failure(Error.CONFLICT);
    }
    @Override public Snapshot resolve(String writer,Resolve input){return inRequest(writer,request->request.resolve(input));}
    private Snapshot resolveInternal(PostgresLoginRequestScope.Request request, Resolve input) {
        request.check(); LoginProof.validate(input, policy);
        return request.transaction(sql -> {
            var proof = sql.fetchOne("select * from sso_login_proofs where issuer = ? and code_digest = ?", input.issuer(), input.codeDigest());
            if (proof == null) throw new Failure(Error.MISSING);
            same(proof.get("binding_hash", byte[].class), LoginProof.bindingHash(input.binding()));
            if (!input.codeKeyId().equals(proof.get("code_key_id", String.class))) throw new Failure(Error.CONFLICT);
            LoginProof.unexpired(time(proof, "expires_at"), now(sql));
            return snapshot(proof);
        });
    }
    /** Bounded owning-store maintenance only; scheduling/monitoring is an integration responsibility.
     * No standalone notice resolver or outbox exists in this disabled slice. */
    public void cleanup() {
        transaction((sql, now) -> {
            sql.execute("""
                update sso_login_events set verified_email = null, identity_id = null where (issuer, event_id) in (
                    select issuer, event_id from sso_login_events where verified_email is not null
                        and recipient_expires_at <= cast(? as timestamptz)
                    order by recipient_expires_at, issuer, event_id limit 100)
                """, at(now));
            sql.execute("""
                delete from sso_login_proof_commands where (requester_hash, command_id) in (
                    select requester_hash, command_id from sso_login_proof_commands where retained_until <= cast(? as timestamptz)
                    order by retained_until, command_id limit 100)
                """, at(now));
            sql.execute("""
                delete from sso_login_proofs where id in (select p.id from sso_login_proofs p
                    where p.retained_until <= cast(? as timestamptz)
                        and not exists (select 1 from sso_login_proof_commands c where c.proof_id = p.id)
                    order by p.retained_until, p.id limit 100)
                """, at(now));
            sql.execute("""
                delete from sso_login_events where (issuer, event_id) in (select e.issuer, e.event_id from sso_login_events e
                    where e.retained_until <= cast(? as timestamptz)
                        and not exists (select 1 from sso_login_proofs p where p.issuer = e.issuer and p.event_id = e.event_id)
                    order by e.retained_until, e.event_id limit 100)
                """, at(now));
            return null;
        });
    }
    private static Snapshot snapshot(Record proof) {
        Kind kind = switch (proof.get("kind", String.class)) {
            case "interactive" -> Kind.INTERACTIVE; case "reuse" -> Kind.REUSE; default -> throw new Failure(Error.UNAVAILABLE);
        };
        UUID event = proof.get("event_id", UUID.class);
        boolean eligible = Boolean.TRUE.equals(proof.get("login_notice_eligible", Boolean.class));
        if ((kind == Kind.INTERACTIVE && (event == null || !eligible)) || (kind == Kind.REUSE && (event != null || eligible))) throw new Failure(Error.UNAVAILABLE);
        return new Snapshot(proof.get("id", UUID.class), proof.get("code_key_id", String.class), event, kind, eligible, time(proof, "issued_at"), time(proof, "expires_at"));
    }
    /** Technical retention-key HMAC; preserves the original domain-separated length-prefixed bytes. */
    public static byte[] recipientDigest(byte[] retentionKey, String recipient) {
        try {
            var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
            for (String field : new String[]{"armoney-login-proof-recipient-v1", recipient}) {
                byte[] value = field.getBytes(StandardCharsets.UTF_8);
                output.writeInt(value.length); output.write(value);
            }
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(retentionKey, "HmacSHA256"));
            return mac.doFinal(bytes.toByteArray());
        } catch (IOException | GeneralSecurityException unavailable) { throw new Failure(Error.UNAVAILABLE); }
    }
    private static void same(byte[] first, byte[] second) {
        if (first == null || second == null || !MessageDigest.isEqual(first, second)) throw new Failure(Error.CONFLICT);
    }
    private static OffsetDateTime at(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
    private static Instant time(Record record, String field) { return record.get(field, OffsetDateTime.class).toInstant(); }
    private static Instant now(DSLContext sql) { return time(sql.fetchOne("select clock_timestamp() as now"), "now"); }
    private static long count(DSLContext sql, String query, Object... values) { return sql.fetchOne(query, values).get("n", Long.class); }
}
