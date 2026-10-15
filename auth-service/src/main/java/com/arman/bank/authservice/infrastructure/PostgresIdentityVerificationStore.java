package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.IdentityVerificationStore;
import com.arman.bank.authservice.domain.IdentityVerification;
import com.arman.bank.authservice.domain.IdentityVerification.*;
import com.arman.bank.authservice.domain.Verification;
import com.arman.bank.authservice.domain.Verification.*;
import com.arman.bank.authservice.domain.Verification.Error;
import com.arman.bank.authservice.domain.VerificationProjection;
import com.arman.bank.runtime.Database;
import java.math.BigDecimal;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Unwired staging repository. It creates neither banking identities nor positive eligibility. */
public final class PostgresIdentityVerificationStore implements IdentityVerificationStore {
    private final Database database;
    private final VerificationCrypto crypto;
    private final byte[] retentionKey,fingerprint;
    private final Policy policy;
    private final PostgresVerificationProjectionStore projections;
    public PostgresIdentityVerificationStore(Database database,VerificationCrypto crypto,byte[] retentionKey,Policy policy,PostgresVerificationProjectionStore projections){
        this.database=Objects.requireNonNull(database);this.crypto=Objects.requireNonNull(crypto);this.policy=Objects.requireNonNull(policy);this.projections=projections;
        if(retentionKey==null||retentionKey.length!=32)throw new IllegalArgumentException("Invalid authority retention key");
        this.retentionKey=retentionKey.clone();this.fingerprint=IdentityVerification.hash("armoney-authority-key-v1",Base64.getEncoder().encodeToString(retentionKey));
    }
    private <T>T transaction(BiFunction<DSLContext,Instant,T> action){return database.transaction(sql->{
        PostgresVerificationProjectionStore.lockControls(sql);
        var config=sql.fetchOne("select retention_key_fingerprint from verification_authority_config where id=1 for update");
        if(config==null)fail(Error.UNAVAILABLE);
        byte[] stored=config.get("retention_key_fingerprint",byte[].class);
        if(stored!=null&&!MessageDigest.isEqual(stored,fingerprint))fail(Error.UNAVAILABLE);
        if(stored==null)sql.execute("update verification_authority_config set retention_key_fingerprint=? where id=1",fingerprint);
        return action.apply(sql,now(sql));
    });}
    private <T>T observed(Flow flow,Observation observation,Instant startedAt,BiFunction<DSLContext,Instant,T> action){
        if(startedAt==null||observation==null||!startedAt.equals(observation.observationStartedAt()))fail(Error.UNAVAILABLE);
        return transaction((sql,now)->{
        IdentityVerification.evidence(flow,observation,now);T result=action.apply(sql,now);
        // Controls already serialize flow writers/cleanup. Re-read without acquiring locks in reverse
        // order after projection work, then sample time AFTER every potentially waiting operation.
        var enrollment=sql.fetchOne("select id,state,expires_at from verification_enrollments where issuer=? and subject=?",flow.issuer(),flow.subject());
        byte[] context=IdentityVerification.contextFingerprint(flow),expected=tuple(flow);
        var binding=sql.fetchOne("select enrollment_id,tuple_digest,expires_at from verification_flows where context_fingerprint=?",context);
        var barrier=sql.fetchOne("select tuple_digest,expires_at from verification_flow_barriers where context_fingerprint=?",context);
        Instant checked=now(sql);IdentityVerification.evidence(flow,observation,checked);
        if(enrollment==null||!checked.isBefore(time(enrollment,"expires_at")))fail(Error.UNAVAILABLE);
        // Initial migration denial has never authorized a flow or an OTP command.
        boolean migrationDenied=result instanceof Verification.Result response&&"BLOCKED_MIGRATION".equals(response.state())
                &&"BLOCKED_MIGRATION".equals(enrollment.get("state"))&&binding==null&&barrier==null;
        if(!migrationDenied&&(binding==null||barrier==null||!enrollment.get("id").equals(binding.get("enrollment_id"))
                ||!MessageDigest.isEqual(expected,binding.get("tuple_digest",byte[].class))
                ||!MessageDigest.isEqual(expected,barrier.get("tuple_digest",byte[].class))
                ||!checked.isBefore(time(binding,"expires_at"))||!checked.isBefore(time(barrier,"expires_at"))))fail(Error.UNAVAILABLE);
        return result;
    });}
    @Override public Instant admit(String writer,Flow flow){
        validate(writer,flow);
        boolean allowed=transaction((sql,now)->{
            byte[] scope=digest("writer",writer);var row=sql.fetchOne("select * from verification_authority_admission where writer_digest=?",scope);
            BigDecimal credits=BigDecimal.valueOf(20);Instant refilled=now;
            if(row==null){if(count(sql,"select count(*) as n from verification_authority_admission")>=16)return false;
                sql.execute("insert into verification_authority_admission(writer_digest,credits,refilled_at) values (?,?,cast(? as timestamptz))",scope,credits,at(now));
            }else{Instant previous=time(row,"refilled_at");if(previous.isAfter(now))refilled=previous;
                credits=row.get("credits",BigDecimal.class).add(BigDecimal.valueOf(Math.max(0,Math.min(10000,Duration.between(previous,now).toMillis())),3).multiply(BigDecimal.valueOf(2))).min(BigDecimal.valueOf(20));}
            boolean permit=credits.compareTo(BigDecimal.ONE)>=0;
            sql.execute("update verification_authority_admission set credits=?,refilled_at=cast(? as timestamptz) where writer_digest=?",permit?credits.subtract(BigDecimal.ONE):credits,at(refilled),scope);
            return permit;
        });
        if(!allowed)fail(Error.RATE_LIMITED);
        return transaction((sql,now)->{preflight(sql,flow,now);return now(sql);});
    }
    private void validate(String writer,Flow flow){IdentityVerification.validate(flow,policy);if(!policy.writers().contains(writer))fail(Error.BINDING);}
    private byte[] tuple(Flow flow){return hmac(IdentityVerification.tuple(flow));}
    private byte[] digest(String... values){return hmac(IdentityVerification.hash(values));}
    private byte[] hmac(byte[] value){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(retentionKey,"HmacSHA256"));return mac.doFinal(value);}catch(GeneralSecurityException unavailable){throw new Failure(Error.UNAVAILABLE);}}
    private void preflight(DSLContext sql,Flow flow,Instant now){
        var barrier=sql.fetchOne("select * from verification_flow_barriers where context_fingerprint=?",IdentityVerification.contextFingerprint(flow));
        if(barrier!=null){if(!now.isBefore(time(barrier,"expires_at"))||!MessageDigest.isEqual(tuple(flow),barrier.get("tuple_digest",byte[].class)))fail(Error.UNAVAILABLE);return;}
        if(count(sql,"select count(*) as n from verification_flow_barriers")>=policy.maxBarriers()||count(sql,"select count(*) as n from verification_flows")>=policy.maxFlows()
                ||count(sql,"select count(*) as n from verification_flow_barriers where created_at>cast(? as timestamptz)",at(now.minusSeconds(3600)))>=1000
                ||count(sql,"select count(*) as n from verification_flow_barriers where subject_digest=? and created_at>cast(? as timestamptz)",digest("subject",flow.issuer(),flow.subject()),at(now.minusSeconds(3600)))>=20)fail(Error.RATE_LIMITED);
        var enrollment=sql.fetchOne("select id,expires_at from verification_enrollments where issuer=? and subject=?",flow.issuer(),flow.subject());
        if(enrollment!=null){if(!now.isBefore(time(enrollment,"expires_at")))fail(Error.UNAVAILABLE);if(count(sql,"select count(*) as n from verification_flows where enrollment_id=? and expires_at>cast(? as timestamptz)",enrollment.get("id",UUID.class),at(now))>=4)fail(Error.RATE_LIMITED);}
    }
    private void authorize(DSLContext sql,Record enrollment,Flow flow,Observation observation,Instant now,boolean replacing){
        IdentityVerification.evidence(flow,observation,now);
        if(enrollment==null||!flow.issuer().equals(enrollment.get("issuer"))||!flow.subject().equals(enrollment.get("subject")))fail(Error.BINDING);
        if(!now.isBefore(time(enrollment,"expires_at")))fail(Error.UNAVAILABLE);
        if(!replacing&&(!Objects.equals(observation.phone(),enrollment.get("phone"))||!Objects.equals(observation.email(),enrollment.get("email"))))fail(Error.CONFLICT);
        var authority=authority(sql,enrollment.get("id",UUID.class));
        if(authority==null)fail(Error.BINDING);
        if(!mappingCurrent(sql,enrollment,authority))fail(Error.CONFLICT);
        if(Set.of("SUSPENDED","BLOCKED_MIGRATION").contains(authority.get("state",String.class)))fail(Error.CONFLICT);
        now=now(sql);IdentityVerification.evidence(flow,observation,now);
        if(!now.isBefore(time(enrollment,"expires_at")))fail(Error.UNAVAILABLE);
        preflight(sql,flow,now);
        byte[] context=IdentityVerification.contextFingerprint(flow),tuple=tuple(flow);
        var existing=sql.fetchOne("select * from verification_flows where context_fingerprint=?",context);
        if(existing!=null){if(!enrollment.get("id").equals(existing.get("enrollment_id"))||!now.isBefore(time(existing,"expires_at"))||!MessageDigest.isEqual(tuple,existing.get("tuple_digest",byte[].class)))fail(Error.BINDING);return;}
        // A permanent barrier without a live row is a denial, never a renewable lease.
        if(sql.fetchOne("select 1 from verification_flow_barriers where context_fingerprint=?",context)!=null)fail(Error.UNAVAILABLE);
        Instant expiry=Collections.min(List.of(now.plusSeconds(600),time(enrollment,"expires_at"),observation.sessionExpiresAt()));
        if(!now.isBefore(expiry))fail(Error.UNAVAILABLE);
        sql.execute("insert into verification_flow_barriers(context_fingerprint,tuple_digest,subject_digest,created_at,expires_at) values (?,?,?,cast(? as timestamptz),cast(? as timestamptz))",context,tuple,digest("subject",flow.issuer(),flow.subject()),at(now),at(expiry));
        sql.execute("insert into verification_flows(context_fingerprint,enrollment_id,tuple_digest,created_at,expires_at) values (?,?,?,cast(? as timestamptz),cast(? as timestamptz))",context,enrollment.get("id",UUID.class),tuple,at(now),at(expiry));
    }
    @Override public Verification.Result enroll(String writer,Enrollment input,Observation observation,Instant startedAt){
        if(input==null||input.commandId()==null)fail(Error.BAD_INPUT);validate(writer,input.flow());
        return observed(input.flow(),observation,startedAt,(sql,now)->{
            IdentityVerification.evidence(input.flow(),observation,now);
            if(!Objects.equals(input.phone(),observation.phone())||!Objects.equals(input.email(),observation.email()))fail(Error.BAD_INPUT);
            var e=sql.fetchOne("select * from verification_enrollments where issuer=? and subject=? for update",input.flow().issuer(),input.flow().subject());
            if(e==null){
                preflight(sql,input.flow(),now);
                if(count(sql,"select count(*) as n from verification_enrollments")>=10000||count(sql,"select count(*) as n from verification_enrollments where created_at>cast(? as timestamptz)",at(now.minusSeconds(3600)))>=1000)fail(Error.RATE_LIMITED);
                UUID id=UUID.randomUUID();var mapping=sql.fetchOne("select identity_id from external_identities where issuer=? and subject=?",input.flow().issuer(),input.flow().subject());
                UUID mapped=mapping==null?null:mapping.get("identity_id",UUID.class);
                boolean blocked=phoneConflict(sql,input.phone(),mapped,null);
                var emailOwner=sql.fetchOne("select id from identities where email=?",input.email());
                if(emailOwner!=null&&!Objects.equals(mapped,emailOwner.get("id",UUID.class)))blocked=true;
                sql.execute("insert into verification_enrollments(id,issuer,subject,binding_digest,binding_key_id,phone,email,state,created_at,expires_at) values (?,?,?,?,?,?,?, ?,cast(? as timestamptz),cast(? as timestamptz))",id,input.flow().issuer(),input.flow().subject(),tuple(input.flow()),crypto.keyId(),input.phone(),input.email(),blocked?"BLOCKED_MIGRATION":"PENDING_CONTACTS",at(now),at(now.plusSeconds(86400)));
                sql.execute("insert into verification_authorities(enrollment_id,pending_identity_id,mapped_identity_id,state,created_at,expires_at) values (?,?,?,?,cast(? as timestamptz),cast(? as timestamptz))",id,mapped==null?UUID.randomUUID():mapped,mapped,blocked?"BLOCKED_MIGRATION":"PENDING_CONTACTS",at(now),at(now.plusSeconds(86400)));
                candidate(sql,id,Purpose.REGISTRATION_EMAIL,input.email(),now);candidate(sql,id,Purpose.REGISTRATION_PHONE,input.phone(),now);
                if(!blocked)reservePhone(sql,id,input.phone());
                e=enrollment(sql,id);
                if(blocked)return response(sql,e,null);
            }
            // Existing V6 legacy binding is not silently imported into V9 authority.
            if(authority(sql,e.get("id",UUID.class))==null)fail(Error.CONFLICT);
            authorize(sql,e,input.flow(),observation,now,true);
            Record current=e;
            return command(sql,now,writer,input.flow(),input.commandId(),new String[]{"enroll",input.phone(),input.email()},()->{
                UUID id=current.get("id",UUID.class);var a=authority(sql,id);
                boolean changed=false;
                for(Purpose purpose:Purpose.values()){
                    String value=purpose==Purpose.REGISTRATION_EMAIL?input.email():input.phone();String column=purpose==Purpose.REGISTRATION_EMAIL?"email":"phone";
                    if(Objects.equals(value,current.get(column)))continue;
                    if(purpose==Purpose.REGISTRATION_PHONE){if(phoneConflict(sql,value,a.get("mapped_identity_id",UUID.class),id))fail(Error.CONFLICT);reservePhone(sql,id,value);}
                    candidate(sql,id,purpose,value,now);changed=true;
                    sql.execute("update verification_challenges set state='SUPERSEDED',digest=null where enrollment_id=? and purpose=? and state in ('PENDING_DELIVERY','OPEN')",id,purpose.name());
                    sql.execute("update verification_delivery_outbox set state='EXPIRED',ciphertext=null,nonce=null,fence=null where enrollment_id=? and purpose=? and state in ('PENDING','LEASED','RETRY_WAIT')",id,purpose.name());
                }
                if(changed){advance(sql,id);sql.execute("update verification_enrollments set phone=?,email=?,state='PENDING_CONTACTS',contact_version=(select revision from verification_authorities where enrollment_id=?) where id=?",input.phone(),input.email(),id,id);}
                return response(sql,enrollment(sql,id),null);
            });
        });
    }
    @Override public Verification.Result status(String writer,UUID id,Flow flow,Observation observation,Instant startedAt){validate(writer,flow);if(id==null)fail(Error.BAD_INPUT);return observed(flow,observation,startedAt,(sql,now)->{var e=enrollment(sql,id);authorize(sql,e,flow,observation,now,false);return response(sql,e,null);});}
    @Override public Verification.Result issue(String writer,Issue input,Observation observation,Instant startedAt){
        if(input==null||input.commandId()==null||input.enrollmentId()==null||input.purpose()==null||input.contactVersion()<1)fail(Error.BAD_INPUT);validate(writer,input.flow());
        return observed(input.flow(),observation,startedAt,(sql,now)->{var e=enrollment(sql,input.enrollmentId());authorize(sql,e,input.flow(),observation,now,false);
            return command(sql,now,writer,input.flow(),input.commandId(),new String[]{"issue",input.enrollmentId().toString(),input.purpose().name(),""+input.contactVersion()},()->{
                var result=new PostgresVerificationStore(database,crypto,sql).issue(new ChallengeCommand(coreCommand(writer,input.flow(),input.commandId()),input.enrollmentId(),binding(input.flow()),input.purpose(),input.contactVersion()));
                return result.error()!=null?result:response(sql,enrollment(sql,input.enrollmentId()),result);
            });});
    }
    @Override public Verification.Result consume(String writer,Consume input,Observation observation,Instant startedAt){
        if(input==null||input.commandId()==null||input.enrollmentId()==null||input.challengeId()==null||input.purpose()==null||input.contactVersion()<1||input.code()==null||!input.code().matches("[0-9]{6}"))fail(Error.BAD_INPUT);validate(writer,input.flow());
        return observed(input.flow(),observation,startedAt,(sql,now)->{var e=enrollment(sql,input.enrollmentId());authorize(sql,e,input.flow(),observation,now,false);
            return command(sql,now,writer,input.flow(),input.commandId(),new String[]{"consume",input.enrollmentId().toString(),input.challengeId().toString(),input.purpose().name(),""+input.contactVersion(),input.code()},()->{
                var result=new PostgresVerificationStore(database,crypto,sql).consume(new ConsumeCommand(coreCommand(writer,input.flow(),input.commandId()),input.enrollmentId(),input.challengeId(),binding(input.flow()),input.purpose(),input.contactVersion(),input.code()));
                if(result.error()!=null)return result;
                advance(sql,input.enrollmentId());
                return response(sql,enrollment(sql,input.enrollmentId()),result);
            });});
    }
    private Verification.Result command(DSLContext sql,Instant now,String writer,Flow flow,UUID command,String[] payload,Supplier<Verification.Result> action){
        var previous=replay(sql,now,writer,flow,command,payload);
        if(previous!=null)return decode(previous.get("response",String.class));
        var result=action.get();
        save(sql,now,writer,flow,command,payload,encode(result));
        return result;
    }
    private Record replay(DSLContext sql,Instant now,String writer,Flow flow,UUID command,String[] payload){
        byte[] requester=digest("requester",writer,flow.issuer(),flow.subject());
        var previous=sql.fetchOne("select * from verification_authority_commands where requester_digest=? and command_id=?",requester,command);
        if(previous!=null){if(!MessageDigest.isEqual(tuple(flow),previous.get("flow_digest",byte[].class))||!MessageDigest.isEqual(digest(payload),previous.get("payload_hash",byte[].class)))fail(Error.CONFLICT);
            if(!now.isBefore(time(previous,"expires_at"))||previous.get("response")==null)fail(Error.UNAVAILABLE);return previous;}
        if(count(sql,"select count(*) as n from verification_authority_commands")>=policy.maxCommands())fail(Error.RATE_LIMITED);
        return null;
    }
    private void save(DSLContext sql,Instant now,String writer,Flow flow,UUID command,String[] payload,String response){
        sql.execute("insert into verification_authority_commands(requester_digest,command_id,flow_digest,payload_hash,response,created_at,expires_at) values (?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz))",digest("requester",writer,flow.issuer(),flow.subject()),command,tuple(flow),digest(payload),response,at(now),at(now.plusSeconds(604800)));
    }
    @Override public VerificationProjection.Status prepare(String writer,UUID commandId,UUID id,Flow flow,Observation observation,Instant startedAt){
        validate(writer,flow);if(commandId==null||id==null)fail(Error.BAD_INPUT);
        return observed(flow,observation,startedAt,(sql,now)->{var e=enrollment(sql,id);authorize(sql,e,flow,observation,now,false);var a=authority(sql,id);
            if(projections==null||a.get("mapped_identity_id")==null)fail(Error.UNAVAILABLE);
            String[] payload={"prepare",id.toString()};var replay=replay(sql,now,writer,flow,commandId,payload);
            if(replay!=null){
                String encoded=replay.get("response",String.class);if(!encoded.startsWith("P|"))fail(Error.CONFLICT);
                UUID operation=UUID.fromString(encoded.substring(2));
                var saved=sql.fetchOne("select * from auth_verification_projection_commands where operation_id=?",operation);
                if(saved==null)fail(Error.UNAVAILABLE);
                return projections.allocateInTransaction(sql,writer,new VerificationProjection.Command(operation,saved.get("identity_id",UUID.class),saved.get("expected_version",Integer.class),saved.get("version",Integer.class),saved.get("canonical_phone",String.class),saved.get("phone_contact_version",Integer.class),saved.get("verification_epoch",Long.class),saved.get("payload_hash",String.class)));
            }
            var current=projectionAuthorityInTransaction(sql,a.get("mapped_identity_id",UUID.class),now);
            IdentityVerification.evidence(flow,observation,now(sql));
            UUID prior=a.get("projection_operation_id",UUID.class);
            if(prior!=null){var tomb=sql.fetchOne("select recovery_barrier,state from auth_verification_projection_tombstones where operation_id=?",prior);
                if(tomb==null||tomb.get("recovery_barrier",Boolean.class)||"PENDING".equals(tomb.get("state")))fail(Error.CONFLICT);}
            if(!now.isBefore(time(e,"expires_at"))||current.emailReceiptId()==null||current.phoneReceiptId()==null)fail(Error.CONFLICT);
            int previous=a.get("projection_revision",Integer.class);long epoch=Math.addExact(a.get("verification_epoch",Long.class),1);
            UUID operation=UUID.randomUUID();var projected=VerificationProjection.create(operation,current.identityId(),previous,Math.addExact(previous,1),current.canonicalPhone(),current.phoneContactVersion(),epoch);
            sql.execute("update verification_authorities set verification_epoch=?,projection_revision=?,projection_operation_id=?,projection_command_id=?,projection_flow_digest=?,projection_writer=?,state='PROJECTION_PENDING' where enrollment_id=?",epoch,projected.version(),operation,commandId,tuple(flow),writer,id);
            var status=projections.allocateInTransaction(sql,writer,projected);
            save(sql,now,writer,flow,commandId,payload,"P|"+operation);
            return status;
        });
    }
    /** Caller-owned authority fixture seam. Production configuration never invokes this supplier. */
    public VerificationProjection.Authority projectionAuthorityInTransaction(DSLContext sql,UUID identity,Instant now){
        PostgresVerificationProjectionStore.requireLocked(sql);
        var a=sql.fetchOne("select * from verification_authorities where mapped_identity_id=? for update",identity);
        if(a==null)throw new VerificationProjection.Failure(VerificationProjection.Error.UNAVAILABLE);
        var e=enrollment(sql,a.get("enrollment_id",UUID.class));
        if(sql.fetchOne("select id from identities where id=? for update",identity)==null)throw new VerificationProjection.Failure(VerificationProjection.Error.CONFLICT);
        now=now(sql);
        if(e==null||!now.isBefore(time(e,"expires_at"))||!mappingCurrent(sql,e,a)||Set.of("SUSPENDED","BLOCKED_MIGRATION").contains(a.get("state",String.class)))throw new VerificationProjection.Failure(VerificationProjection.Error.CONFLICT);
        var email=candidate(sql,a,Purpose.REGISTRATION_EMAIL);var phone=candidate(sql,a,Purpose.REGISTRATION_PHONE);
        UUID emailReceipt=receipt(sql,email),phoneReceipt=receipt(sql,phone);
        if(emailReceipt==null||phoneReceipt==null||phoneConflict(sql,phone.get("canonical_value",String.class),identity,e.get("id",UUID.class)))throw new VerificationProjection.Failure(VerificationProjection.Error.CONFLICT);
        if(!now(sql).isBefore(time(e,"expires_at")))throw new VerificationProjection.Failure(VerificationProjection.Error.CONFLICT);
        return new VerificationProjection.Authority(identity,e.get("id",UUID.class),phone.get("canonical_value",String.class),phone.get("version",Integer.class),email.get("version",Integer.class),a.get("verification_epoch",Long.class),emailReceipt,phoneReceipt);
    }
    @Override public boolean attach(String writer,VerificationProjection.Claim claim,VerificationProjection.Result result){
        if(writer==null||!policy.writers().contains(writer)||claim==null||!writer.equals(claim.writer())||result==null)fail(Error.BAD_INPUT);
        if(projections==null)fail(Error.UNAVAILABLE);
        return transaction((sql,now)->{
            var a=sql.fetchOne("select * from verification_authorities where mapped_identity_id=? for update",claim.command().identityId());
            if(a==null||!writer.equals(a.get("projection_writer",String.class)))return false;
            VerificationProjection.Authority current=null;
            try{current=projectionAuthorityInTransaction(sql,claim.command().identityId(),now);}
            catch(VerificationProjection.Failure rejected){if(rejected.error()!=VerificationProjection.Error.CONFLICT)throw rejected;}
            boolean matches=current!=null&&current.matches(claim.command())&&claim.command().operationId().equals(a.get("projection_operation_id",UUID.class));
            if("PROJECTED".equals(a.get("state")))return matches&&result.outcome()==VerificationProjection.Outcome.ACK&&result.acknowledgement().matches(claim.command());
            boolean finished=projections.finishInTransaction(sql,claim,result);
            var tomb=sql.fetchOne("select state from auth_verification_projection_tombstones where operation_id=?",claim.command().operationId());
            if(matches&&finished&&result.outcome()==VerificationProjection.Outcome.ACK&&result.acknowledgement().matches(claim.command())&&tomb!=null&&"ACKNOWLEDGED".equals(tomb.get("state")))
                sql.execute("update verification_authorities set state='PROJECTED',revision=revision+1 where enrollment_id=?",a.get("enrollment_id",UUID.class));
            return finished;
        });
    }
    @Override public void cleanup(){transaction((sql,now)->{
        sql.execute("delete from verification_flows where context_fingerprint in (select context_fingerprint from verification_flows where expires_at<=cast(? as timestamptz) order by expires_at,context_fingerprint limit 100)",at(now));
        sql.execute("update verification_authority_commands set response=null where (requester_digest,command_id) in (select requester_digest,command_id from verification_authority_commands where expires_at<=cast(? as timestamptz) and response is not null order by expires_at,command_id limit 100)",at(now));return null;
    });}
    private static Record enrollment(DSLContext sql,UUID id){return sql.fetchOne("select * from verification_enrollments where id=? for update",id);}
    private static Record authority(DSLContext sql,UUID id){return sql.fetchOne("select * from verification_authorities where enrollment_id=? for update",id);}
    private static boolean mappingCurrent(DSLContext sql,Record enrollment,Record authority){
        var mapping=sql.fetchOne("select identity_id from external_identities where issuer=? and subject=? for update",enrollment.get("issuer",String.class),enrollment.get("subject",String.class));
        return Objects.equals(authority.get("mapped_identity_id",UUID.class),mapping==null?null:mapping.get("identity_id",UUID.class));
    }
    private static Record candidate(DSLContext sql,Record authority,Purpose purpose){UUID id=authority.get(purpose==Purpose.REGISTRATION_EMAIL?"email_candidate_id":"phone_candidate_id",UUID.class);if(id==null)fail(Error.UNAVAILABLE);return sql.fetchOne("select * from verification_candidates where id=?",id);}
    private static UUID receipt(DSLContext sql,Record candidate){var row=sql.fetchOne("select id from verification_receipts where candidate_id=? and enrollment_id=? and purpose=? and contact_version=?",candidate.get("id",UUID.class),candidate.get("enrollment_id",UUID.class),candidate.get("purpose",String.class),candidate.get("version",Integer.class));return row==null?null:row.get("id",UUID.class);}
    private static void candidate(DSLContext sql,UUID enrollment,Purpose purpose,String value,Instant now){
        var latest=sql.fetchOne("select max(version) as version from verification_candidates where enrollment_id=? and purpose=?",enrollment,purpose.name());Integer old=latest.get("version",Integer.class);int version=old==null?1:Math.addExact(old,1);UUID id=UUID.randomUUID();
        sql.execute("insert into verification_candidates(id,enrollment_id,purpose,version,canonical_value,created_at) values (?,?,?,?,?,cast(? as timestamptz))",id,enrollment,purpose.name(),version,value,at(now));
        sql.execute(purpose==Purpose.REGISTRATION_EMAIL?"update verification_authorities set email_candidate_id=? where enrollment_id=?":"update verification_authorities set phone_candidate_id=? where enrollment_id=?",id,enrollment);
    }
    private static boolean phoneConflict(DSLContext sql,String phone,UUID identity,UUID enrollment){
        var owner=sql.fetchOne("select id from identities where registration_phone=?",phone);if(owner!=null&&!Objects.equals(identity,owner.get("id",UUID.class)))return true;
        for(String table:List.of("verification_phone_claims","verification_authority_phone_claims")){var claim=sql.fetchOne("select enrollment_id from "+table+" where phone=?",phone);if(claim!=null&&!Objects.equals(enrollment,claim.get("enrollment_id",UUID.class)))return true;}return false;
    }
    private static void reservePhone(DSLContext sql,UUID id,String phone){sql.execute("insert into verification_authority_phone_claims(phone,enrollment_id) values (?,?) on conflict(phone) do nothing",phone,id);}
    private static void advance(DSLContext sql,UUID id){sql.execute("update verification_authorities set revision=revision+1,verification_epoch=verification_epoch+1,state='PENDING_CONTACTS' where enrollment_id=?",id);}
    private static Binding binding(Flow flow){return new Binding(flow.issuer(),flow.subject(),flow.authenticationSessionId());}
    private UUID coreCommand(String writer,Flow flow,UUID command){return UUID.nameUUIDFromBytes(digest("core-command",writer,Base64.getEncoder().encodeToString(tuple(flow)),command.toString()));}
    private static Verification.Result response(DSLContext sql,Record e,Verification.Result result){var a=authority(sql,e.get("id",UUID.class));var email=candidate(sql,a,Purpose.REGISTRATION_EMAIL);var phone=candidate(sql,a,Purpose.REGISTRATION_PHONE);int revision=a.get("revision",Integer.class);
        return new Verification.Result(e.get("id",UUID.class),result==null?null:result.challengeId(),result==null?null:result.receiptId(),revision,a.get("state",String.class),time(e,"expires_at"),result==null?null:result.resendAt(),result==null?0:result.attemptsRemaining(),null,receipt(sql,email)!=null,receipt(sql,phone)!=null,revision,email.get("version",Integer.class),phone.get("version",Integer.class));}
    private static String encode(Verification.Result r){return String.join("|",text(r.enrollmentId()),text(r.challengeId()),text(r.receiptId()),""+r.contactVersion(),r.state(),text(r.expiresAt()),text(r.resendAt()),""+r.attemptsRemaining(),text(r.error()),""+r.emailVerified(),""+r.phoneVerified(),""+r.version(),""+r.emailContactVersion(),""+r.phoneContactVersion());}
    private static Verification.Result decode(String value){String[] a=value.split("\\|",-1);return new Verification.Result(uuid(a[0]),uuid(a[1]),uuid(a[2]),Integer.parseInt(a[3]),a[4],instant(a[5]),instant(a[6]),Integer.parseInt(a[7]),a[8].isEmpty()?null:Error.valueOf(a[8]),Boolean.parseBoolean(a[9]),Boolean.parseBoolean(a[10]),Integer.parseInt(a[11]),Integer.parseInt(a[12]),Integer.parseInt(a[13]));}
    private static UUID uuid(String value){return value.isEmpty()?null:UUID.fromString(value);}
    private static Instant instant(String value){return value.isEmpty()?null:Instant.parse(value);}
    private static String text(Object value){return value==null?"":value.toString();}
    private static Instant now(DSLContext sql){return sql.fetchOne("select clock_timestamp() as now").get("now",OffsetDateTime.class).toInstant();}
    private static Instant time(Record row,String field){return row.get(field,OffsetDateTime.class).toInstant();}
    private static OffsetDateTime at(Instant now){return now.atOffset(ZoneOffset.UTC);}
    private static long count(DSLContext sql,String query,Object... args){return sql.fetchOne(query,args).get("n",Long.class);}
    private static void fail(Error error){throw new Failure(error);}
}
