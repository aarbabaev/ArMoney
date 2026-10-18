package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LiveUserRevision;
import com.arman.bank.runtime.Database;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Caller-authenticated denial/classification protocol. Never grants banking access or guesses legacy ownership. */
public final class PostgresMigrationUserMutationStore {
    private final PostgresLoginRequestScope requests;private final PostgresVerificationProjectionStore projection;
    private final LegacyMigrationManagedProofCrypto crypto;private final String projectionWriter;
    private static final ObjectMapper JSON=new ObjectMapper();
    public static final class Rejected extends RuntimeException {
        private final int status;public Rejected(int status){super("User mutation rejected");this.status=status;}public int status(){return status;}
    }
    public PostgresMigrationUserMutationStore(Database database,PostgresVerificationProjectionStore projection,
            String projectionWriter,LegacyMigrationManagedProofCrypto crypto){
        requests=new PostgresLoginRequestScope(Objects.requireNonNull(database));this.projection=Objects.requireNonNull(projection);
        if(projectionWriter==null||!projectionWriter.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("User mutation store rejected");
        this.projectionWriter=projectionWriter;this.crypto=Objects.requireNonNull(crypto);
    }
    public <T> T inRequest(String writer,java.util.function.Function<Request,T> action){
        return requests.inRequest(writer,owned->{Request request=new Request(writer,owned);try{return action.apply(request);}finally{request.closed=true;}});
    }
    public final class Request {
        private final String writer;private final PostgresLoginRequestScope.Request owned;private boolean closed;
        private Request(String writer,PostgresLoginRequestScope.Request owned){this.writer=writer;this.owned=owned;}
        public Map<String,Object> suspend(Map<String,Object> input,long originalStarted){
            if(closed)throw new Rejected(503);owned.verify();final Map<String,Object> request;
            try{request=LiveUserRevision.suspensionRequest(writer,input);}catch(RuntimeException invalid){throw new Rejected(400);}
            var response=owned.transaction(sql->apply(sql,writer,request,originalStarted));owned.verify();return response;
        }
        @Override public String toString(){return "UserMutationRequest[redacted]";}
    }
    public Map<String,Object> suspend(String writer,Map<String,Object> input,long originalStarted){return inRequest(writer,request->request.suspend(input,originalStarted));}
    private Map<String,Object> apply(DSLContext sql,String writer,Map<String,Object> request,long started){
        remaining(started);UUID operation=UUID.fromString((String)request.get("operation_id")),identity=UUID.fromString((String)request.get("identity_id"));
        PostgresLegacyMigrationStore.lockOwner(sql,identity);
        String canonical=encode(request);
        Record previous=sql.fetchOne("select * from auth_user_mutation_receipts where writer_id=? and operation_id=?",writer,operation);
        if(previous!=null){
            if(!identity.equals(previous.get("identity_id",UUID.class))||!request.get("payload_hash").equals(previous.get("request_hash"))||!canonical.equals(previous.get("canonical_request")))throw new Rejected(409);
            var result=LiveUserRevision.suspensionResponse(request,HttpMigrationUserCurrent.parse(previous.get("response_json",String.class).getBytes(java.nio.charset.StandardCharsets.UTF_8)));remaining(started);return result;
        }
        if(sql.fetchOne("select count(*) as n from auth_user_mutation_receipts").get("n",Long.class)>=100000)throw new Rejected(503);
        var original=projection.requireOriginalMutation(sql,projectionWriter,operation,identity,LiveUserRevision.object(request.get("after"),false));
        Record source=sql.fetchOne("select * from managed_identity_sources where identity_id=? for update",identity);
        Map<String,Object> binding=null;String outcome;
        if(source!=null&&SourceEligibilitySql.MIGRATION.equals(source.get("source_kind"))){
            if(request.get("before")==null||request.get("baseline")==null||!source.get("source_id").equals(source.get("migration_decision_id")))throw new Rejected(503);
            var current=SourceEligibilitySql.requireMutationSource(sql,identity,crypto);Record a=current.authority();
            var baseline=LiveUserRevision.object(request.get("baseline"),false);
            requireEqual(baseline.get("migration_id"),a.get("migration_id",UUID.class).toString());requireEqual(baseline.get("decision_id"),a.get("decision_id",UUID.class).toString());
            requireEqual(baseline.get("publication_revision"),a.get("publication_revision"));requireEqual(baseline.get("final_ack_hash"),a.get("final_ack_hash"));
            boolean active="ACTIVE".equals(a.get("state"));SourceEligibilitySql.requireUserTuple(sql,current,LiveUserRevision.object(request.get("before"),false),active);
            long epoch=a.get("verification_epoch",Long.class),revision=a.get("source_revision",Long.class);
            if(active)SourceEligibilitySql.suspend(sql,current,"LOCAL_MUTATION",crypto);
            var after=SourceEligibilitySql.requireMutationSource(sql,identity,crypto);Record resulting=after.authority();
            if(!"SUSPENDED".equals(resulting.get("state")))throw new Rejected(503);
            var b=new LinkedHashMap<String,Object>();b.put("source_kind","MIG");b.put("migration_id",a.get("migration_id",UUID.class).toString());b.put("decision_id",a.get("decision_id",UUID.class).toString());b.put("classification_generation",a.get("classification_generation"));b.put("previous_epoch",epoch);b.put("previous_source_revision",revision);b.put("resulting_epoch",resulting.get("verification_epoch"));b.put("resulting_source_revision",resulting.get("source_revision"));binding=Collections.unmodifiableMap(b);outcome="SUSPENDED";
        }else{
            requireRegistration(sql,identity,source,original,request);outcome="NOT_MIG";
        }
        // Repeat original command/local source after all work, without requiring ACK while User is processing it.
        projection.requireOriginalMutation(sql,projectionWriter,operation,identity,LiveUserRevision.object(request.get("after"),false));
        if(binding==null)requireRegistration(sql,identity,source,original,request);
        else {var checked=SourceEligibilitySql.requireMutationSource(sql,identity,crypto);requireEqual(checked.authority().get("verification_epoch"),binding.get("resulting_epoch"));requireEqual(checked.authority().get("source_revision"),binding.get("resulting_source_revision"));}
        Instant now=sql.fetchOne("select clock_timestamp() as now").get("now",OffsetDateTime.class).toInstant();if(!now.isBefore(original.deadline()))throw new Rejected(503);remaining(started);
        var result=new LinkedHashMap<String,Object>();result.put("protocol_version",1L);result.put("operation_id",operation.toString());result.put("identity_id",identity.toString());result.put("payload_hash",request.get("payload_hash"));result.put("outcome",outcome);result.put("binding",binding);result.put("recorded_at_us",Math.addExact(Math.multiplyExact(now.getEpochSecond(),1000000L),now.getNano()/1000));
        var checked=LiveUserRevision.suspensionResponse(request,result);
        sql.execute("""
            insert into auth_user_mutation_receipts(writer_id,operation_id,identity_id,request_hash,canonical_request,outcome,response_json,recorded_at,
              source_kind,migration_id,decision_id,classification_generation,previous_epoch,previous_source_revision,resulting_epoch,resulting_source_revision)
            values(?,?,?,?,?,?,?,cast(? as timestamptz),?,?,?,?,?,?,?,?)
            """,writer,operation,identity,request.get("payload_hash"),canonical,outcome,encode(checked),now.atOffset(ZoneOffset.UTC),
            binding==null?null:"MIG",binding==null?null:UUID.fromString((String)binding.get("migration_id")),binding==null?null:UUID.fromString((String)binding.get("decision_id")),binding==null?null:binding.get("classification_generation"),binding==null?null:binding.get("previous_epoch"),binding==null?null:binding.get("previous_source_revision"),binding==null?null:binding.get("resulting_epoch"),binding==null?null:binding.get("resulting_source_revision"));
        projection.requireOriginalMutation(sql,projectionWriter,operation,identity,LiveUserRevision.object(request.get("after"),false));
        if(binding==null)requireRegistration(sql,identity,source,original,request);
        else {var finalSource=SourceEligibilitySql.requireMutationSource(sql,identity,crypto);requireEqual(finalSource.authority().get("verification_epoch"),binding.get("resulting_epoch"));requireEqual(finalSource.authority().get("source_revision"),binding.get("resulting_source_revision"));}
        if(!sql.fetchOne("select clock_timestamp() < cast(? as timestamptz) as live",original.deadline().atOffset(ZoneOffset.UTC)).get("live",Boolean.class))throw new Rejected(503);remaining(started);return checked;
    }
    private void requireRegistration(DSLContext sql,UUID owner,Record source,PostgresVerificationProjectionStore.OriginalMutation original,Map<String,Object> request){
        if(request.get("baseline")!=null||pending(sql,owner))throw new Rejected(503);
        Record marker=sql.fetchOne("select * from managed_registration_principals where identity_id=?",owner);if(marker==null||!original.enrollmentId().equals(marker.get("enrollment_id",UUID.class)))throw new Rejected(503);
        if(source!=null&&(!"REGISTRATION_DUAL_CONTACT".equals(source.get("source_kind"))||!original.enrollmentId().equals(source.get("source_id"))||!original.enrollmentId().equals(source.get("registration_enrollment_id"))||source.get("migration_decision_id")!=null))throw new Rejected(503);
        Record a=sql.fetchOne("select * from verification_authorities where enrollment_id=? for update",original.enrollmentId());
        if(a==null||!owner.equals(a.get("mapped_identity_id"))||!owner.equals(a.get("pending_identity_id"))||!Set.of("PROJECTION_PENDING","PROJECTED").contains(a.get("state"))||!original.command().operationId().equals(a.get("projection_operation_id"))||!projectionWriter.equals(a.get("projection_writer")))throw new Rejected(503);
        requireEqual(a.get("projection_revision"),original.command().version());requireEqual(a.get("verification_epoch"),original.command().verificationEpoch());
        for(String purpose:List.of("REGISTRATION_EMAIL","REGISTRATION_PHONE")){
            boolean email=purpose.equals("REGISTRATION_EMAIL");UUID receipt=email?original.emailReceiptId():original.phoneReceiptId();
            UUID candidate=a.get(email?"email_candidate_id":"phone_candidate_id",UUID.class);
            int version=email?original.emailContactVersion():original.command().phoneContactVersion();
            Record row=sql.fetchOne("""
                select c.canonical_value from verification_candidates c join verification_receipts r
                  on r.candidate_id=c.id and r.enrollment_id=c.enrollment_id and r.purpose=c.purpose and r.contact_version=c.version
                 join verification_challenges h on h.id=r.challenge_id and h.candidate_id=c.id and h.enrollment_id=c.enrollment_id and h.purpose=c.purpose and h.contact_version=c.version
                where c.id=? and c.enrollment_id=? and c.purpose=? and c.version=? and r.id=? and h.state='CONSUMED'
                for update of r,h
                """,candidate,original.enrollmentId(),purpose,version,receipt);
            if(row==null||(!email&&!original.command().canonicalPhone().equals(row.get("canonical_value"))))throw new Rejected(503);
        }

        if(request.get("before")==null&&(original.command().expectedVersion()!=0||original.command().version()!=1||sql.fetchOne("select operation_id from registration_completion_intents where operation_id=? and identity_id=? and enrollment_id=? and verification_epoch=? and projection_revision=?",original.command().operationId(),owner,original.enrollmentId(),original.command().verificationEpoch(),original.command().version())==null))throw new Rejected(503);
        if(!sql.fetchOne("select clock_timestamp()<expires_at as live from verification_authorities where enrollment_id=?",original.enrollmentId()).get("live",Boolean.class))throw new Rejected(503);
    }
    private static boolean pending(DSLContext sql,UUID owner){
        // Static identifiers only. Every known owner-bound state counts, including expired/incomplete records.
        for(String table:List.of("legacy_migration_intents","legacy_migration_publication_decisions","legacy_migration_verified_final_acks","legacy_migration_final_commands","legacy_migration_final_completions","migration_banking_authorities","migration_banking_authority_current","migration_banking_grants","legacy_migration_owner_contacts")){
            String query=switch(table){
                case "legacy_migration_intents"->"select 1 from legacy_migration_intents where identity_id=? limit 1";
                case "legacy_migration_publication_decisions"->"select 1 from legacy_migration_publication_decisions where identity_id=? limit 1";
                case "legacy_migration_verified_final_acks"->"select 1 from legacy_migration_verified_final_acks where identity_id=? limit 1";
                case "legacy_migration_final_commands"->"select 1 from legacy_migration_final_commands where identity_id=? limit 1";
                case "legacy_migration_final_completions"->"select 1 from legacy_migration_final_completions where identity_id=? limit 1";
                case "migration_banking_authorities"->"select 1 from migration_banking_authorities where identity_id=? limit 1";
                case "migration_banking_authority_current"->"select 1 from migration_banking_authority_current where identity_id=? limit 1";
                case "migration_banking_grants"->"select 1 from migration_banking_grants where identity_id=? limit 1";
                case "legacy_migration_owner_contacts"->"select 1 from legacy_migration_owner_contacts where identity_id=? limit 1";default->throw new Rejected(503);};
            if(sql.fetchOne(query,owner)!=null)return true;
        }
        return sql.fetchOne("""
            select 1 where exists(select 1 from legacy_migration_account_receipts where owner_id=?)
             or exists(select 1 from legacy_migration_managed_attempts where owner_id=?) or exists(select 1 from legacy_migration_managed_receipts where owner_id=?)
             or exists(select 1 from legacy_migration_managed_targets where owner_id=?) or exists(select 1 from legacy_migration_contact_baselines where owner_id=?)
             or exists(select 1 from legacy_migration_email_candidates where owner_id=?) or exists(select 1 from legacy_migration_contact_transitions where owner_id=?)
            """,owner,owner,owner,owner,owner,owner,owner)!=null;
    }
    static void remaining(long start){long elapsed=System.nanoTime()-start;if(elapsed<0||elapsed>=4000000000L||Thread.currentThread().isInterrupted())throw new Rejected(503);}
    private static void requireEqual(Object a,Object b){if(a instanceof Number x&&b instanceof Number y){if(x.longValue()!=y.longValue())throw new Rejected(503);}else if(!Objects.equals(a,b))throw new Rejected(503);}
    private static String encode(Map<String,Object> value){try{String text=JSON.writeValueAsString(value);if(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096)throw new Rejected(503);return text;}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new Rejected(503);}}
}
