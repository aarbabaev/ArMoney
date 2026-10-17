package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.BankingEligibilityStore;
import com.arman.bank.authservice.domain.BankingEligibility;
import com.arman.bank.authservice.domain.BankingEligibility.Grant;
import com.arman.bank.authservice.domain.IdentityVerification;
import com.arman.bank.authservice.domain.IdentityVerification.*;
import com.arman.bank.authservice.domain.Verification;
import com.arman.bank.authservice.domain.VerificationProjection;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Caller-owned auth transaction only. No remote I/O, standalone lookup, session grant or public ACTIVE. */
public final class PostgresBankingEligibilityStore implements BankingEligibilityStore {
    private static final String FLOW_CONTEXT=PostgresBankingEligibilityStore.class.getName()+".authorized-flow";
    private record AuthorizedFlow(DSLContext context,long transaction,UUID enrollment,Flow flow,Observation observation,Instant admittedAt,byte[] tupleDigest) {
        private AuthorizedFlow {tupleDigest=tupleDigest.clone();}
    }
    private final DSLContext sql;
    public PostgresBankingEligibilityStore(DSLContext sql) {
        this.sql=Objects.requireNonNull(sql);
        PostgresVerificationProjectionStore.requireLocked(sql);
    }
    private record Current(Record enrollment, Record authority, Record email, Record phone,
                           UUID identity, UUID emailReceipt, UUID phoneReceipt, VerificationProjection.Command projection,
                           byte[] association, Instant deadline) {}
    /** Called only after the owning adapter verifies its existing tuple HMAC/live barrier in this transaction. */
    public static void authorizedFlow(DSLContext sql,UUID enrollment,Flow flow,Observation observation,Instant admittedAt,byte[] tupleDigest) {
        PostgresVerificationProjectionStore.requireLocked(sql);
        if(tupleDigest==null||tupleDigest.length!=32)fail(Verification.Error.UNAVAILABLE);
        sql.configuration().data(FLOW_CONTEXT,new AuthorizedFlow(sql,sql.fetchOne("select txid_current()").get(0,Long.class),enrollment,flow,observation,admittedAt,tupleDigest));
    }
    private void requireLiveFlow(UUID enrollment,Flow flow,Observation observation,Instant admittedAt) {
        requireAuthorized(enrollment,flow,observation,admittedAt);
        var context=(AuthorizedFlow)sql.configuration().data(FLOW_CONTEXT);
        var binding=sql.fetchOne("select * from verification_flows where context_fingerprint=?",IdentityVerification.contextFingerprint(flow));
        var barrier=sql.fetchOne("select * from verification_flow_barriers where context_fingerprint=?",IdentityVerification.contextFingerprint(flow));
        Instant checked=now();
        BankingEligibility.fresh(flow,observation,admittedAt,checked);
        if(binding==null||barrier==null||!enrollment.equals(binding.get("enrollment_id",UUID.class))
                ||!MessageDigest.isEqual(context.tupleDigest(),binding.get("tuple_digest",byte[].class))
                ||!MessageDigest.isEqual(context.tupleDigest(),barrier.get("tuple_digest",byte[].class))
                ||!checked.isBefore(time(binding,"expires_at"))||!checked.isBefore(time(barrier,"expires_at")))fail(Verification.Error.UNAVAILABLE);
    }
    private void requireAuthorized(UUID enrollment,Flow flow,Observation observation,Instant admittedAt) {
        PostgresVerificationProjectionStore.requireLocked(sql);
        Object value=sql.configuration().data(FLOW_CONTEXT);
        if(!(value instanceof AuthorizedFlow current)||current.context()!=sql
                ||current.transaction()!=sql.fetchOne("select txid_current()").get(0,Long.class)
                ||!Objects.equals(current.enrollment(),enrollment)||!Objects.equals(current.flow(),flow)
                ||current.observation()!=observation||!Objects.equals(current.admittedAt(),admittedAt))fail(Verification.Error.UNAVAILABLE);
    }

    @Override public Optional<Grant> record(UUID enrollment,Flow flow,Observation observation,Instant admittedAt) {
        PostgresVerificationProjectionStore.requireLocked(sql);
        requireAuthorized(enrollment,flow,observation,admittedAt);
        requireLiveFlow(enrollment,flow,observation,admittedAt);
        BankingEligibility.fresh(flow,observation,admittedAt,now());
        var authority=sql.fetchOne("select state from verification_authorities where enrollment_id=?",enrollment);
        if(authority==null||!"PROJECTED".equals(authority.get("state",String.class)))return Optional.empty();
        Current current=current(enrollment,flow,observation,admittedAt);
        long epoch=current.authority().get("verification_epoch",Long.class);
        var original=sql.fetchOne("select * from banking_eligibility_grants where identity_id=? and verification_epoch=?",current.identity(),epoch);
        if(original!=null){
            if(!matches(original,current))fail(Verification.Error.CONFLICT);
            finalCheck(original,enrollment,flow,observation,admittedAt);
            return Optional.of(snapshot(original));
        }
        if(sql.fetchOne("select count(*) from banking_eligibility_grants").get(0,Long.class)>=100000)fail(Verification.Error.RATE_LIMITED);
        Instant created=now();BankingEligibility.fresh(flow,observation,admittedAt,created);
        if(!created.isBefore(current.deadline()))fail(Verification.Error.UNAVAILABLE);
        var command=current.projection();
        sql.execute("""
            insert into banking_eligibility_grants(identity_id,verification_epoch,enrollment_id,association_digest,
                email_candidate_id,phone_candidate_id,email_receipt_id,phone_receipt_id,email_contact_version,
                phone_contact_version,projection_operation_id,projection_revision,projection_payload_hash,
                created_at,expires_at,enrollment_expires_at,authority_expires_at)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz))
            """,current.identity(),epoch,enrollment,current.association(),current.email().get("id",UUID.class),current.phone().get("id",UUID.class),
            current.emailReceipt(),current.phoneReceipt(),current.email().get("version",Integer.class),current.phone().get("version",Integer.class),
            command.operationId(),command.version(),command.payloadHash(),at(created),at(current.deadline()),
            at(time(current.enrollment(),"expires_at")),at(time(current.authority(),"expires_at")));
        var saved=sql.fetchOne("select * from banking_eligibility_grants where identity_id=? and verification_epoch=?",current.identity(),epoch);
        finalCheck(saved,enrollment,flow,observation,admittedAt);
        return Optional.of(snapshot(saved));
    }
    @Override public boolean invalidateIfChanged(UUID enrollment,Flow flow,Observation observation,Instant admittedAt) {
        PostgresVerificationProjectionStore.requireLocked(sql);
        requireAuthorized(enrollment,flow,observation,admittedAt);
        BankingEligibility.fresh(flow,observation,admittedAt,now());
        var original=latest(enrollment);
        if(original==null)return false;
        var e=sql.fetchOne("select issuer,subject from verification_enrollments where id=?",enrollment);
        if(e==null||!flow.issuer().equals(e.get("issuer"))||!flow.subject().equals(e.get("subject")))fail(Verification.Error.BINDING);
        boolean changed=!observation.enabled()||!observation.sessionActive();
        if(!changed){
            try{changed=!matches(original,current(enrollment,flow,observation,admittedAt));}
            catch(Verification.Failure rejected){if(rejected.error()!=Verification.Error.CONFLICT)throw rejected;changed=true;}
        }
        BankingEligibility.fresh(flow,observation,admittedAt,now());
        if(changed)suspend(enrollment,original.get("verification_epoch",Long.class));
        requireLiveFlow(enrollment,flow,observation,admittedAt);
        return changed;
    }
    @Override public void invalidateAfterMutation(UUID enrollment) {
        PostgresVerificationProjectionStore.requireLocked(sql);
        var original=latest(enrollment);
        if(original!=null)suspend(enrollment,original.get("verification_epoch",Long.class));
    }
    private Record latest(UUID enrollment) {
        return sql.fetchOne("select * from banking_eligibility_grants where enrollment_id=? order by verification_epoch desc limit 1",enrollment);
    }
    private void finalCheck(Record grant,UUID enrollment,Flow flow,Observation observation,Instant admittedAt) {
        Current current=current(enrollment,flow,observation,admittedAt);
        if(!matches(grant,current))fail(Verification.Error.CONFLICT);
        requireLiveFlow(enrollment,flow,observation,admittedAt);
        Instant checked=now();
        BankingEligibility.fresh(flow,observation,admittedAt,checked);
        if(!checked.isBefore(time(grant,"expires_at"))||!checked.isBefore(current.deadline()))fail(Verification.Error.UNAVAILABLE);
    }
    private void suspend(UUID enrollment,long grantedEpoch) {
        var a=sql.fetchOne("select state,verification_epoch from verification_authorities where enrollment_id=? for update",enrollment);
        if(a==null||"SUSPENDED".equals(a.get("state",String.class)))return;
        long current=a.get("verification_epoch",Long.class);
        long invalidated=current>grantedEpoch?current:Math.addExact(grantedEpoch,1);
        sql.execute("update verification_authorities set state='SUSPENDED',verification_epoch=?,revision=revision+1 where enrollment_id=?",invalidated,enrollment);
    }
    private Current current(UUID enrollment,Flow flow,Observation observation,Instant admittedAt) {
        BankingEligibility.fresh(flow,observation,admittedAt,now());
        IdentityVerification.evidence(flow,observation,now());
        Current result=currentLocal(enrollment,flow.issuer(),flow.subject(),observation.email(),observation.phone());
        BankingEligibility.fresh(flow,observation,admittedAt,now());
        return result;
    }
    @Override public com.arman.bank.authservice.domain.LoginProof.CurrentEvidence requireForProof(
            com.arman.bank.authservice.domain.LoginProof.Binding binding,
            com.arman.bank.authservice.domain.LoginProof.Observation observation,Instant admittedAt){
        PostgresVerificationProjectionStore.requireLocked(sql);
        com.arman.bank.authservice.domain.LoginProof.observation(binding,observation,admittedAt,now());
        var e=sql.fetchOne("select id from verification_enrollments where issuer=? and subject=?",binding.issuer(),binding.subject());
        if(e==null)fail(Verification.Error.CONFLICT);
        Current current=currentLocal(e.get("id",UUID.class),binding.issuer(),binding.subject(),observation.email(),observation.phone());
        var grant=sql.fetchOne("select * from banking_eligibility_grants where identity_id=? and verification_epoch=?",current.identity(),current.projection().verificationEpoch());
        if(grant==null||!matches(grant,current))fail(Verification.Error.CONFLICT);
        Instant deadline=Collections.min(List.of(current.deadline(),time(grant,"expires_at"),observation.sessionDeadline(),admittedAt.plusSeconds(5)));
        var result=new com.arman.bank.authservice.domain.LoginProof.CurrentEvidence(binding,snapshot(grant),current.email().get("canonical_value",String.class),admittedAt,deadline);
        Instant checked=now();
        com.arman.bank.authservice.domain.LoginProof.observation(binding,observation,admittedAt,checked);
        if(!result.completeFor(binding,checked))fail(Verification.Error.UNAVAILABLE);
        return result;
    }
    private Current currentLocal(UUID enrollment,String issuer,String subject,String emailValue,String phoneValue) {
        var e=sql.fetchOne("select * from verification_enrollments where id=? for update",enrollment);
        var a=sql.fetchOne("select * from verification_authorities where enrollment_id=? for update",enrollment);
        var marker=sql.fetchOne("select identity_id from managed_registration_principals where enrollment_id=?",enrollment);
        if(e==null||a==null||marker==null||!issuer.equals(e.get("issuer"))||!subject.equals(e.get("subject")))fail(Verification.Error.CONFLICT);
        UUID identity=marker.get("identity_id",UUID.class);
        if(!identity.equals(a.get("mapped_identity_id",UUID.class))||!identity.equals(a.get("pending_identity_id",UUID.class))
                ||!"PROJECTED".equals(a.get("state"))||!"VERIFIED".equals(e.get("state")))fail(Verification.Error.CONFLICT);
        if(sql.fetchOne("select id from identities where id=? for update",identity)==null
                ||sql.fetchOne("select identity_id from external_identities where issuer=? and subject=? and identity_id=? for update",issuer,subject,identity)==null)fail(Verification.Error.CONFLICT);
        Instant deadline=Collections.min(List.of(time(e,"expires_at"),time(a,"expires_at")));
        if(!now().isBefore(deadline))fail(Verification.Error.UNAVAILABLE);
        var email=candidate(a,"email_candidate_id",enrollment,"REGISTRATION_EMAIL");
        var phone=candidate(a,"phone_candidate_id",enrollment,"REGISTRATION_PHONE");
        if(!Objects.equals(emailValue,email.get("canonical_value"))||!Objects.equals(phoneValue,phone.get("canonical_value"))
                ||!Objects.equals(e.get("email"),email.get("canonical_value"))||!Objects.equals(e.get("phone"),phone.get("canonical_value")))fail(Verification.Error.CONFLICT);
        if(sql.fetchOne("select phone from verification_authority_phone_claims where phone=? and enrollment_id=?",phone.get("canonical_value"),enrollment)==null)fail(Verification.Error.CONFLICT);
        if(sql.fetchOne("select id from identities where registration_phone=? and id<>?",phone.get("canonical_value"),identity)!=null)fail(Verification.Error.CONFLICT);
        UUID emailReceipt=receipt(email),phoneReceipt=receipt(phone);
        UUID operation=a.get("projection_operation_id",UUID.class);
        var command=sql.fetchOne("select * from auth_verification_projection_commands where operation_id=?",operation);
        if(command==null)fail(Verification.Error.CONFLICT);
        var tomb=sql.fetchOne("select * from auth_verification_projection_tombstones where writer_hash=? and operation_id=?",command.get("writer_hash",byte[].class),operation);
        if(tomb==null||!"ACKNOWLEDGED".equals(tomb.get("state"))||Boolean.TRUE.equals(tomb.get("recovery_barrier")))fail(Verification.Error.CONFLICT);
        deadline=Collections.min(List.of(deadline,time(command,"expires_at"),time(tomb,"expires_at")));
        if(!now().isBefore(deadline))fail(Verification.Error.UNAVAILABLE);
        var projection=new VerificationProjection.Command(command.get("operation_id",UUID.class),command.get("identity_id",UUID.class),command.get("expected_version",Integer.class),
                command.get("version",Integer.class),command.get("canonical_phone",String.class),command.get("phone_contact_version",Integer.class),command.get("verification_epoch",Long.class),command.get("payload_hash",String.class));
        try{VerificationProjection.validate(projection);}catch(VerificationProjection.Failure mismatch){fail(Verification.Error.CONFLICT);}
        var persistedAck=new VerificationProjection.Ack(tomb.get("operation_id",UUID.class),projection.identityId(),tomb.get("version",Integer.class),tomb.get("phone_contact_version",Integer.class),tomb.get("verification_epoch",Long.class),tomb.get("payload_hash",String.class));
        if(!persistedAck.matches(projection)||!identity.equals(projection.identityId())||!enrollment.equals(command.get("enrollment_id",UUID.class))
                ||a.get("verification_epoch",Long.class)!=projection.verificationEpoch()||a.get("projection_revision",Integer.class)!=projection.version()
                ||!Objects.equals(phone.get("canonical_value"),projection.canonicalPhone())||phone.get("version",Integer.class)!=projection.phoneContactVersion()
                ||!emailReceipt.equals(command.get("email_receipt_id",UUID.class))||!phoneReceipt.equals(command.get("phone_receipt_id",UUID.class))
                ||!email.get("version",Integer.class).equals(command.get("email_contact_version",Integer.class)))fail(Verification.Error.CONFLICT);
        if(sql.fetchOne("select operation_id from registration_completion_intents where enrollment_id=? and identity_id=? and operation_id=? and verification_epoch=? and projection_revision=?",
                enrollment,identity,operation,projection.verificationEpoch(),projection.version())==null)fail(Verification.Error.CONFLICT);
        byte[] association=IdentityVerification.hash("armoney-eligibility-association-v1",issuer,subject,identity.toString());
        return new Current(e,a,email,phone,identity,emailReceipt,phoneReceipt,projection,association,deadline);
    }
    private Record candidate(Record authority,String column,UUID enrollment,String purpose) {
        var row=sql.fetchOne("select * from verification_candidates where id=? and enrollment_id=? and purpose=?",authority.get(column,UUID.class),enrollment,purpose);
        if(row==null)fail(Verification.Error.CONFLICT);return row;
    }
    private UUID receipt(Record candidate) {
        var row=sql.fetchOne("select r.id from verification_receipts r join verification_challenges c on c.id=r.challenge_id where r.candidate_id=? and r.enrollment_id=? and r.purpose=? and r.contact_version=? and c.state='CONSUMED'",candidate.get("id",UUID.class),candidate.get("enrollment_id",UUID.class),candidate.get("purpose",String.class),candidate.get("version",Integer.class));
        if(row==null)fail(Verification.Error.CONFLICT);return row.get("id",UUID.class);
    }
    private static boolean matches(Record grant,Current current) {
        return grant.get("identity_id",UUID.class).equals(current.identity())
                &&grant.get("verification_epoch",Long.class)==current.projection().verificationEpoch()
                &&grant.get("email_candidate_id",UUID.class).equals(current.email().get("id",UUID.class))
                &&grant.get("phone_candidate_id",UUID.class).equals(current.phone().get("id",UUID.class))
                &&grant.get("email_receipt_id",UUID.class).equals(current.emailReceipt())&&grant.get("phone_receipt_id",UUID.class).equals(current.phoneReceipt())
                &&grant.get("email_contact_version",Integer.class).equals(current.email().get("version",Integer.class))
                &&grant.get("phone_contact_version",Integer.class)==current.projection().phoneContactVersion()
                &&grant.get("projection_operation_id",UUID.class).equals(current.projection().operationId())
                &&grant.get("projection_revision",Integer.class)==current.projection().version()
                &&grant.get("projection_payload_hash",String.class).equals(current.projection().payloadHash())
                &&!time(grant,"expires_at").isAfter(current.deadline())
                &&MessageDigest.isEqual(grant.get("association_digest",byte[].class),current.association());
    }
    private static Grant snapshot(Record row) {
        return new Grant(row.get("identity_id",UUID.class),row.get("enrollment_id",UUID.class),row.get("verification_epoch",Long.class),row.get("email_candidate_id",UUID.class),row.get("phone_candidate_id",UUID.class),
                row.get("email_receipt_id",UUID.class),row.get("phone_receipt_id",UUID.class),row.get("email_contact_version",Integer.class),row.get("phone_contact_version",Integer.class),
                row.get("projection_operation_id",UUID.class),row.get("projection_revision",Integer.class),row.get("projection_payload_hash",String.class),time(row,"created_at"),time(row,"expires_at"));
    }
    private Instant now(){return time(sql.fetchOne("select clock_timestamp() as now"),"now");}
    private static Instant time(Record row,String field){return row.get(field,OffsetDateTime.class).toInstant();}
    private static OffsetDateTime at(Instant value){return value.atOffset(ZoneOffset.UTC);}
    private static void fail(Verification.Error error){throw new Verification.Failure(error);}
}
