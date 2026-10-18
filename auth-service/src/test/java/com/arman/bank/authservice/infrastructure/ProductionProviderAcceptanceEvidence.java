package com.arman.bank.authservice.infrastructure;

import com.arman.bank.runtime.Database;
import com.arman.bank.authservice.domain.Verification;
import org.jooq.DSLContext;
import org.jooq.Record;
import java.time.*;
import java.util.*;
import static com.arman.bank.authservice.infrastructure.ProductionProviderAcceptanceController.*;

/** Auth-owned persisted reads only; does not enroll, issue, consume, project, record grants or confirm. */
final class ProductionProviderAcceptanceEvidence {
    private final Database database;
    final String issuer,username,email;
    ProductionProviderAcceptanceEvidence(Database database,String issuer,String username,String email){
        this.database=Objects.requireNonNull(database);this.issuer=Objects.requireNonNull(issuer);this.username=Objects.requireNonNull(username);this.email=Objects.requireNonNull(email);
        URIValidation.validate(issuer,username,email);
    }
    private static final class URIValidation {
        static void validate(String issuer,String username,String email){
            var uri=java.net.URI.create(issuer);
            if(!"https".equals(uri.getScheme())||uri.getHost()==null||!"/sso/realms/armoney".equals(uri.getPath())||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                ||!username.matches("\\+9715[024568][0-9]{7}")||!email.matches("[a-z0-9._+\\-]+@[a-z0-9.\\-]+\\.(test|invalid)"))throw new Rejected(503);
        }
    }
    <T>T read(Deadline deadline,java.util.function.Function<DSLContext,T> work){
        deadline.remaining();return database.transaction(sql->{
            // Transaction-local settings, never a persistent connection change.
            sql.fetchOne("select set_config('statement_timeout',?,true),set_config('lock_timeout',?,true)",Long.toString(Math.min(500,deadline.millis())),Long.toString(Math.min(500,deadline.millis())));
            PostgresVerificationProjectionStore.lockControls(sql);deadline.remaining();
            T result=work.apply(sql);deadline.remaining();return result;
        });
    }
    void requireFresh(Deadline deadline){read(deadline,sql->{
        for(String table:List.of("identities","verification_enrollments","verification_authorities","verification_receipts","verification_challenges","verification_delivery_outbox",
            "managed_registration_principals","managed_identity_sources","external_identities","legacy_migration_intents","banking_eligibility_grants","sessions","managed_banking_sessions","sso_login_events","sso_login_proofs","sso_code_intents","sso_login_creation_reservations","sso_code_confirmation_results")){
            deadline.remaining();if(sql.fetchOne("select exists(select 1 from "+table+")").get(0,Boolean.class))throw new Rejected(503); // Closed static table identifiers only.
        }return null;
    });}
    private Record enrollment(DSLContext sql){
        var rows=sql.fetch("select * from verification_enrollments where issuer=? and phone=? and email=? limit 2",issuer,username,email);
        if(rows.size()>1)throw new Rejected(503);return rows.isEmpty()?null:rows.getFirst();
    }
    record Delivery(UUID deliveryId,UUID challengeId,UUID enrollmentId,UUID owner,String purpose,int version,String destination,Instant expiresAt) {
        @Override public String toString(){return "ProductionAcceptanceDeliveryMetadata[redacted]";}
    }
    Delivery currentDelivery(String purpose,Deadline deadline){
        if(!Set.of("REGISTRATION_EMAIL","REGISTRATION_PHONE").contains(purpose))throw new Rejected(400);
        return read(deadline,sql->{
            Record e=enrollment(sql);if(e==null)throw new Rejected(503);
            var rows=sql.fetch("""
                select d.id as delivery_id,c.id as challenge_id,e.id as enrollment_id,a.pending_identity_id,
                    c.purpose,c.contact_version,v.canonical_value,c.expires_at
                from verification_enrollments e join verification_authorities a on a.enrollment_id=e.id
                join verification_candidates v on v.id=case when ?='REGISTRATION_EMAIL' then a.email_candidate_id else a.phone_candidate_id end
                join verification_challenges c on c.enrollment_id=e.id and c.candidate_id=v.id and c.purpose=v.purpose and c.contact_version=v.version
                join verification_delivery_outbox d on d.challenge_id=c.id and d.enrollment_id=e.id and d.purpose=c.purpose and d.contact_version=c.contact_version
                where e.id=? and c.purpose=? and c.state='OPEN' and d.state='ACCEPTED'
                and c.expires_at>clock_timestamp() and d.expires_at>clock_timestamp() and e.expires_at>clock_timestamp() and a.expires_at>clock_timestamp()
                and a.state not in ('SUSPENDED','BLOCKED_MIGRATION') limit 2
                """,purpose,e.get("id",UUID.class),purpose);
            if(rows.size()!=1)throw new Rejected(503);var row=rows.getFirst();String destination=row.get("canonical_value",String.class);
            if(!Objects.equals(destination,purpose.equals("REGISTRATION_EMAIL")?email:username))throw new Rejected(503);
            return new Delivery(row.get("delivery_id",UUID.class),row.get("challenge_id",UUID.class),row.get("enrollment_id",UUID.class),row.get("pending_identity_id",UUID.class),purpose,row.get("contact_version",Integer.class),destination,row.get("expires_at",OffsetDateTime.class).toInstant());
        });
    }
    /** Verify scope while the genuine worker owns its actual fenced lease. */
    UUID leaseOwner(PostgresVerificationStore.DeliveryLease lease,String destination,Deadline deadline){return read(deadline,sql->{
        Record e=enrollment(sql);if(e==null||!lease.enrollmentId().equals(e.get("id",UUID.class)))throw new Rejected(503);
        if(!Objects.equals(destination,lease.purpose()==Verification.Purpose.REGISTRATION_EMAIL?email:username))throw new Rejected(503);
        var row=sql.fetchOne("select a.pending_identity_id from verification_authorities a join verification_delivery_outbox d on d.enrollment_id=a.enrollment_id where d.id=? and d.challenge_id=? and d.fence=? and d.state='LEASED' and d.lease_until>clock_timestamp()",lease.deliveryId(),lease.challengeId(),lease.fence());
        if(row==null)throw new Rejected(503);return row.get("pending_identity_id",UUID.class);
    });}
    Map<String,?> outcomes(Deadline deadline){return read(deadline,sql->{
        Record e=enrollment(sql);if(e==null)return outcomes(false,false,0,List.of());
        UUID id=e.get("id",UUID.class);var a=sql.fetchOne("select * from verification_authorities where enrollment_id=?",id);
        if(a==null)throw new Rejected(503);UUID owner=a.get("pending_identity_id",UUID.class);
        boolean projected=projected(sql,e,a);deadline.remaining();boolean active=false;
        try{new PostgresBankingEligibilityStore(sql).requireCurrentLocalRegistration(issuer,e.get("subject",String.class));active=true;}
        catch(Verification.Failure denied){if(denied.error()!=Verification.Error.CONFLICT&&denied.error()!=Verification.Error.UNAVAILABLE)throw denied;}
        int sessions=sql.fetchOne("select (select count(*) from sessions where identity_id=?)+(select count(*) from managed_banking_sessions where identity_id=?)",owner,owner).get(0,Integer.class);
        var events=sql.fetch("""
            select v.event_id from sso_code_confirmation_results r
            join sso_login_proofs p on p.id=r.proof_id
            join sso_login_events v on v.issuer=p.issuer and v.event_id=p.event_id
            where v.issuer=? and v.identity_id=? and p.kind='interactive' and p.login_notice_eligible=true
            order by v.event_id limit 17
            """,issuer,owner);
        return outcomes(active,projected,sessions,confirmedEventIds(events.getValues("event_id",String.class)));
    });}
    static List<String> confirmedEventIds(List<?> events){
        if(events==null||events.size()>16)throw new Rejected(503);var ids=new TreeSet<String>();
        for(Object item:events){
            if(!(item instanceof String id))throw new Rejected(503);
            try{if(!UUID.fromString(id).toString().equals(id)||!ids.add(id))throw new Rejected(503);}
            catch(IllegalArgumentException malformed){throw new Rejected(503);}
        }
        return List.copyOf(ids); // Complete bounded inventory; never choose a latest event or silently deduplicate.
    }
    private static Map<String,?> outcomes(boolean active,boolean projected,int sessions,List<String> events){
        Map<String,Object> result=new LinkedHashMap<>();result.put("registration_active",active);result.put("projection_acknowledged",projected);result.put("bank_session_count",sessions);result.put("confirmed_event_ids",events);return Collections.unmodifiableMap(result);
    }
    private boolean projected(DSLContext sql,Record e,Record a){
        return sql.fetchOne("""
            select exists(select 1 from auth_verification_projection_commands c
            join auth_verification_projection_tombstones t on t.operation_id=c.operation_id and t.writer_hash=c.writer_hash
            join registration_completion_intents i on i.operation_id=c.operation_id and i.enrollment_id=c.enrollment_id and i.identity_id=c.identity_id
            join managed_registration_principals m on m.identity_id=c.identity_id and m.enrollment_id=c.enrollment_id
            join external_identities x on x.identity_id=c.identity_id and x.issuer=? and x.subject=?
            join verification_candidates ec on ec.id=? and ec.enrollment_id=c.enrollment_id and ec.purpose='REGISTRATION_EMAIL'
            join verification_candidates pc on pc.id=? and pc.enrollment_id=c.enrollment_id and pc.purpose='REGISTRATION_PHONE'
            join verification_receipts er on er.id=c.email_receipt_id and er.candidate_id=ec.id and er.contact_version=ec.version
            join verification_receipts pr on pr.id=c.phone_receipt_id and pr.candidate_id=pc.id and pr.contact_version=pc.version
            join verification_challenges ech on ech.id=er.challenge_id and ech.state='CONSUMED'
            join verification_challenges pch on pch.id=pr.challenge_id and pch.state='CONSUMED'
            where c.operation_id=? and c.enrollment_id=? and c.identity_id=? and c.identity_id=?
            and ?='PROJECTED' and ?='VERIFIED' and cast(? as timestamptz)>clock_timestamp() and cast(? as timestamptz)>clock_timestamp()
            and t.state='ACKNOWLEDGED' and not t.recovery_barrier and c.expires_at>clock_timestamp() and t.expires_at>clock_timestamp()
            and c.version=t.version and c.phone_contact_version=t.phone_contact_version and c.verification_epoch=t.verification_epoch and c.payload_hash=t.payload_hash
            and c.version=? and c.verification_epoch=? and c.email_contact_version=ec.version and c.phone_contact_version=pc.version
            and c.canonical_phone=pc.canonical_value and pc.canonical_value=? and ec.canonical_value=?
            and i.verification_epoch=c.verification_epoch and i.projection_revision=c.version)
            """,issuer,e.get("subject"),a.get("email_candidate_id"),a.get("phone_candidate_id"),a.get("projection_operation_id"),e.get("id"),a.get("mapped_identity_id"),a.get("pending_identity_id"),a.get("state"),e.get("state"),e.get("expires_at"),a.get("expires_at"),a.get("projection_revision"),a.get("verification_epoch"),username,email).get(0,Boolean.class);
    }
    @Override public String toString(){return "ProductionProviderAcceptanceEvidence[redacted]";}
}
