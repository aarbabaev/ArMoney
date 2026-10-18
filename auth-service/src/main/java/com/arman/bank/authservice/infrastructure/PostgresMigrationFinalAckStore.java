package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LegacyMigrationFinalization;
import com.arman.bank.authservice.domain.LegacyMigrationFinalization.Failure;
import com.arman.bank.authservice.domain.LegacyMigrationFinalization.Error;
import com.arman.bank.authservice.domain.LegacyMigrationOtp;
import com.arman.bank.authservice.domain.LegacyMigrationContact;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.security.MessageDigest;
import com.arman.bank.runtime.Database;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

/** Auth-owned technical User publication and immutable ACK custody. Never a banking credential. */
public final class PostgresMigrationFinalAckStore implements AutoCloseable {
    static final List<String> FINAL=List.of("protocol_version","migration_id","decision_id","stage_operation_id","command_id","identity_id","profile_id","baseline_generation","contact_mutation_revision","baseline_hash","stage_ack_hash","publication_revision","email_contact_version","email_receipt_id","email_receipt_generation","phone_receipt_id","phone_receipt_generation","receipt_snapshot_hash","payload_hash","final_ack_hash");
    static final String ACK_DOMAIN="armoney-legacy-migration-final-ack-provenance-v1",COMMAND_DOMAIN="armoney-legacy-migration-final-command-v1",COMPLETION_DOMAIN="armoney-legacy-migration-final-completion-v1";
    private static final URI USER=URI.create("http://user-service:8080");
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final long BUDGET=4_000_000_000L;
    private final HttpClient http;
    private final String publicationKey;
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private final Set<Body> bodies=ConcurrentHashMap.newKeySet();
    private final Semaphore calls=new Semaphore(16);
    private volatile boolean closed;
    private final Database database;
    private final LegacyMigrationCrypto accountCrypto;
    private final LegacyMigrationManagedProofCrypto crypto;
    private final PostgresLegacyMigrationFinalizationStore journals;
    private final PostgresLegacyMigrationOtpStore otp;
    private final MigrationBindingSqlPort binding;
    private final PostgresMigrationBankingEligibilityStore consumer;
    private final String actor;
    private final URI userOrigin;
    public PostgresMigrationFinalAckStore(Database database,LegacyMigrationCrypto accountCrypto,LegacyMigrationManagedProofCrypto crypto,PostgresLegacyMigrationFinalizationStore journals,PostgresLegacyMigrationOtpStore otp,MigrationBindingSqlPort binding,PostgresMigrationBankingEligibilityStore consumer,String actor,String publicationKey){
        this(database,accountCrypto,crypto,journals,otp,binding,consumer,actor,publicationKey,USER,newClient(publicationKey,actor,database,accountCrypto,crypto,journals,otp,binding,consumer));
    }
    private static HttpClient newClient(String key,String actor,Object... dependencies){for(Object dependency:dependencies)Objects.requireNonNull(dependency);if(actor==null||!actor.matches("[A-Za-z0-9_-]{1,64}")||key==null||!key.matches("[!-~]{32,256}"))throw new IllegalArgumentException("Invalid migration publication credential");return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();}
    private PostgresMigrationFinalAckStore(Database database,LegacyMigrationCrypto accountCrypto,LegacyMigrationManagedProofCrypto crypto,PostgresLegacyMigrationFinalizationStore journals,PostgresLegacyMigrationOtpStore otp,MigrationBindingSqlPort binding,PostgresMigrationBankingEligibilityStore consumer,String actor,String publicationKey,URI origin,HttpClient http){
        this.database=Objects.requireNonNull(database);this.accountCrypto=Objects.requireNonNull(accountCrypto);this.crypto=Objects.requireNonNull(crypto);this.journals=Objects.requireNonNull(journals);this.otp=Objects.requireNonNull(otp);this.binding=Objects.requireNonNull(binding);this.consumer=Objects.requireNonNull(consumer);
        if(actor==null||!actor.matches("[A-Za-z0-9_-]{1,64}")||publicationKey==null||!publicationKey.matches("[!-~]{32,256}"))throw new IllegalArgumentException("Invalid migration publication settings");this.actor=actor;this.publicationKey=publicationKey;userOrigin=origin;this.http=http;
    }
    static PostgresMigrationFinalAckStore synthetic(Database database,LegacyMigrationCrypto accountCrypto,LegacyMigrationManagedProofCrypto crypto,PostgresLegacyMigrationFinalizationStore journals,PostgresLegacyMigrationOtpStore otp,MigrationBindingSqlPort binding,PostgresMigrationBankingEligibilityStore consumer,String actor,String key,URI origin){
        if(!"true".equals(System.getProperty("armoney.test.isolated.user.fixture"))||origin==null||!"http".equals(origin.getScheme())||!"127.0.0.1".equals(origin.getHost())||origin.getPort()<1||origin.getPort()>65535||origin.getRawUserInfo()!=null||origin.getRawQuery()!=null||origin.getRawFragment()!=null||!origin.getRawPath().isEmpty())throw new IllegalArgumentException("Root isolated User fixture required");
        return new PostgresMigrationFinalAckStore(database,accountCrypto,crypto,journals,otp,binding,consumer,actor,key,origin,newClient(key,actor,database,accountCrypto,crypto,journals,otp,binding,consumer));
    }    private void check(){if(closed||Thread.currentThread().isInterrupted())throw new Failure(Error.UNAVAILABLE);}
    private static long remaining(long started){long elapsed=System.nanoTime()-started;if(elapsed<0||elapsed>=BUDGET)throw new Failure(Error.UNAVAILABLE);return BUDGET-elapsed;}
    private Map<String,Object> call(String operation,Map<String,Object> tuple,long started){
        check();if(!Set.of("user-publish","user-publication-result","user-resolve").contains(operation))throw new Failure(Error.UNAVAILABLE);
        Body body=new Body();CompletableFuture<HttpResponse<byte[]>> future=null;
        try{
            byte[] bytes=JSON.writeValueAsBytes(tuple);if(bytes.length>12288)throw new Failure(Error.UNAVAILABLE);
            var request=HttpRequest.newBuilder(userOrigin.resolve("/v1/internal/migrations/legacy/"+operation)).timeout(Duration.ofNanos(remaining(started))).header("Content-Type","application/json").header("X-ArMoney-Migration-Publication-Key",publicationKey).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            bodies.add(body);check();future=http.sendAsync(request,ignored->body);pending.add(future);check();
            var response=future.get(remaining(started),TimeUnit.NANOSECONDS);check();remaining(started);
            if(response.statusCode()!=200||response.headers().allValues("Content-Type").size()!=1||!response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().equalsIgnoreCase("application/json"))throw new Failure(Error.UNAVAILABLE);
            return LegacyMigrationFinalizationRoutes.parse(response.body());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new Failure(Error.UNAVAILABLE);}
        catch(Exception unavailable){throw new Failure(Error.UNAVAILABLE);}
        finally{if(future!=null)future.cancel(true);body.cancel();bodies.remove(body);if(future!=null)pending.remove(future);}
    }
    /** Technical publication/ACK recovery only; expiration never grants a final capability. */
    public Map<String,Object> publish(UUID migration,String ownerCapability){return capture(migration,ownerCapability,false);}
    public Map<String,Object> publicationResult(UUID migration,String ownerCapability){return capture(migration,ownerCapability,true);}
    private Map<String,Object> capture(UUID migration,String capability,boolean resultOnly){
        check();LegacyMigrationFinalization.id(migration);if(!calls.tryAcquire())throw new Failure(Error.RATE_LIMITED);long started=System.nanoTime();
        try{return new PostgresLoginRequestScope(database).inRequest(actor,owned->captureOwned(owned,migration,capability,resultOnly,started));}finally{calls.release();}
    }
    Map<String,Object> publish(PostgresLoginRequestScope.Request owned,UUID migration,String capability){return capture(owned,migration,capability,false);}
    Map<String,Object> publicationResult(PostgresLoginRequestScope.Request owned,UUID migration,String capability){return capture(owned,migration,capability,true);}
    private Map<String,Object> capture(PostgresLoginRequestScope.Request owned,UUID migration,String capability,boolean resultOnly){
        check();LegacyMigrationFinalization.id(migration);if(owned==null)throw new Failure(Error.UNAVAILABLE);owned.check();if(!actor.equals(owned.actor()))throw new Failure(Error.UNAVAILABLE);
        if(!calls.tryAcquire())throw new Failure(Error.RATE_LIMITED);long started=System.nanoTime();
        try{return captureOwned(owned,migration,capability,resultOnly,started);}finally{calls.release();}
    }
    private Map<String,Object> captureOwned(PostgresLoginRequestScope.Request owned,UUID migration,String capability,boolean resultOnly,long started){
        owned.check();if(!actor.equals(owned.actor()))throw new Failure(Error.UNAVAILABLE);remaining(started);check();
            Map<String,Object> journal=owned.transaction(sql->{Map<String,Object> j=journal(sql,migration,capability);Record stored=sql.fetchOne("select * from legacy_migration_verified_final_acks where decision_id=?",j.get("decision_id"));if(stored!=null)verifiedAck(sql,j,stored);return j;});
            Map<String,Object> prior=owned.transaction(sql->{Record stored=sql.fetchOne("select * from legacy_migration_verified_final_acks where decision_id=?",journal.get("decision_id"));return stored==null?null:verifiedAck(sql,journal,stored);});
            if(prior!=null){owned.verify();return prior;}
            Map<String,Object> raw=call(resultOnly?"user-publication-result":"user-publish",tuple(journal),started);owned.verify();remaining(started);check();
            Map<String,Object> ack=owned.transaction(sql->{
                Map<String,Object> current=journal(sql,migration,capability);if(!current.equals(journal))throw new Failure(Error.CONFLICT);
                Map<String,Object> validated=validateAck(raw,current,stage(sql,migration));Record old=sql.fetchOne("select * from legacy_migration_verified_final_acks where decision_id=?",journal.get("decision_id"));
                if(old!=null){Map<String,Object> stored=verifiedAck(sql,current,old);if(!stored.equals(validated))throw new Failure(Error.CONFLICT);return stored;}
                Instant verified=normalized(now(sql));String encoded=json(validated);String provenance=ackHash(current,validated,encoded,verified);
                var row=new LinkedHashMap<String,Object>();for(String field:FINAL){Object value=validated.get(field);String column=field.equals("command_id")?"publication_command_id":field;if(field.endsWith("_id"))value=LegacyMigrationFinalization.uuid(value);row.put(column,value);}
                row.put("journal_hash",current.get("journal_hash"));row.put("retention_key_id",current.get("retention_key_id"));row.put("provenance_hash",provenance);row.put("ack_json",encoded);row.put("verified_at",verified);
                remaining(started);check();insert(sql,"legacy_migration_verified_final_acks",row);remaining(started);check();return validated;
            });owned.verify();remaining(started);check();return ack;
    }
    private Map<String,Object> journal(DSLContext sql,UUID migration,String capability){
        check();if(capability==null||!capability.matches("[A-Za-z0-9_-]{43}"))throw new Failure(Error.UNAUTHORIZED);
        var retained=journals.requireJournal(sql,migration);retained.requireCurrent(sql);Map<String,Object> j=retained.fields();if(!actor.equals(j.get("writer_id")))throw new Failure(Error.CONFLICT);
        Record intent=sql.fetchOne("select identity_id,retention_key_id,capability_hash from legacy_migration_intents where id=?",migration);
        if(intent==null||!j.get("identity_id").equals(intent.get("identity_id",UUID.class))||!MessageDigest.isEqual(intent.get("capability_hash",byte[].class),accountCrypto.capability(intent.get("retention_key_id",String.class),migration,intent.get("identity_id",UUID.class),capability)))throw new Failure(Error.UNAUTHORIZED);
        return Collections.unmodifiableMap(new LinkedHashMap<>(j));
    }
    private static Map<String,Object> tuple(Map<String,Object> j){var value=new LinkedHashMap<String,Object>();for(String key:List.of("migration_id","decision_id","stage_operation_id"))value.put(key,scalar(j.get(key)));value.put("command_id",scalar(j.get("publication_command_id")));value.put("payload_hash",j.get("payload_hash"));return Collections.unmodifiableMap(value);}
    private static Map<String,Object> stage(DSLContext sql,UUID migration){
        Record row=sql.fetchOne("select ack_json,stage_ack_hash from legacy_migration_otp_stage_acks where migration_id=?",migration);if(row==null)throw new Failure(Error.UNAVAILABLE);
        Map<String,Object> ack=LegacyMigrationFinalizationRoutes.parse(row.get("ack_json",String.class).getBytes(java.nio.charset.StandardCharsets.UTF_8));LegacyMigrationFinalization.exact(ack,LegacyMigrationOtp.ACK);
        if(!LegacyMigrationOtp.hash("armoney-user-migration-stage-ack-v1",LegacyMigrationOtp.ACK.subList(0,LegacyMigrationOtp.ACK.size()-1),ack).equals(row.get("stage_ack_hash",String.class)))throw new Failure(Error.UNAVAILABLE);return Collections.unmodifiableMap(ack);
    }
    private Map<String,Object> verifiedAck(DSLContext sql,Map<String,Object> j,Record row){return verifiedAck(sql,j,row,crypto);}
    private static Map<String,Object> verifiedAck(DSLContext sql,Map<String,Object> j,Record row,LegacyMigrationManagedProofCrypto crypto){
        Map<String,Object> decoded=LegacyMigrationFinalizationRoutes.parse(row.get("ack_json",String.class).getBytes(java.nio.charset.StandardCharsets.UTF_8));Map<String,Object> ack=validateAck(decoded,j,stage(sql,(UUID)j.get("migration_id")));
        for(String field:FINAL){String column=field.equals("command_id")?"publication_command_id":field;if(!scalar(ack.get(field)).equals(scalar(row.get(column))))throw new Failure(Error.UNAVAILABLE);}
        if(!j.get("journal_hash").equals(row.get("journal_hash"))||!j.get("retention_key_id").equals(row.get("retention_key_id"))||!json(ack).equals(row.get("ack_json",String.class))||!ackHash(crypto,j,ack,json(ack),row.get("verified_at",OffsetDateTime.class).toInstant()).equals(row.get("provenance_hash",String.class)))throw new Failure(Error.UNAVAILABLE);return ack;
    }
    private String ackHash(Map<String,Object> j,Map<String,Object> ack,String encoded,Instant verified){return ackHash(crypto,j,ack,encoded,verified);}
    private static String ackHash(LegacyMigrationManagedProofCrypto crypto,Map<String,Object> j,Map<String,Object> ack,String encoded,Instant verified){
        return hex(crypto.hash((String)j.get("retention_key_id"),ACK_DOMAIN,"1",scalar(j.get("journal_hash")),scalar(j.get("decision_id")),scalar(j.get("identity_id")),scalar(j.get("migration_id")),scalar(j.get("stage_operation_id")),scalar(j.get("publication_command_id")),scalar(j.get("payload_hash")),scalar(j.get("stage_ack_hash")),scalar(j.get("baseline_hash")),scalar(j.get("receipt_snapshot_hash")),scalar(ack.get("final_ack_hash")),scalar(ack.get("publication_revision")),encoded,LegacyMigrationContact.instant(verified)));
    }
    private static void insert(DSLContext sql,String table,Map<String,Object> row){List<String> names=new ArrayList<>(row.keySet());Object[] values=names.stream().map(k->{Object v=row.get(k);return v instanceof Instant time?OffsetDateTime.ofInstant(time,ZoneOffset.UTC):v;}).toArray();sql.insertInto(DSL.table(table)).columns(names.stream().map(DSL::field).toArray(org.jooq.Field[]::new)).values(values).execute();}
    private static Instant now(DSLContext sql){return sql.fetchOne("select clock_timestamp()").get(0,OffsetDateTime.class).toInstant();}
    private static Instant normalized(Instant value){return value.truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    private static String hex(byte[] bytes){return HexFormat.of().formatHex(bytes);}
    private static final class Scope {
        final DSLContext sql;final PostgresLoginRequestScope.Request request;final long tx;final int pid;final OffsetDateTime backend;final Thread thread=Thread.currentThread();
        Scope(DSLContext sql,PostgresLoginRequestScope.Request request){this.sql=sql;this.request=request;Record row=sql.fetchOne("select txid_current(),pg_backend_pid(),backend_start from pg_stat_activity where pid=pg_backend_pid()");tx=row.get(0,Long.class);pid=row.get(1,Integer.class);backend=row.get(2,OffsetDateTime.class);}
        void check(DSLContext current){request.check();if(current!=sql||thread!=Thread.currentThread()||Thread.currentThread().isInterrupted())throw new Failure(Error.UNAVAILABLE);PostgresVerificationProjectionStore.requireLocked(current);Record row=current.fetchOne("select txid_current(),pg_backend_pid(),backend_start from pg_stat_activity where pid=pg_backend_pid()");if(row==null||tx!=row.get(0,Long.class)||pid!=row.get(1,Integer.class)||!backend.equals(row.get(2,OffsetDateTime.class)))throw new Failure(Error.UNAVAILABLE);}
    }
    final class VerifiedFinalAckUse {
        private final Scope scope;private final Map<String,Object> journal,ack;private final String provenance;private final Instant verified;
        private final PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence;
        private VerifiedFinalAckUse(Scope scope,Map<String,Object> journal,Record row,PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence){this.scope=scope;this.journal=journal;ack=verifiedAck(scope.sql,journal,row);provenance=row.get("provenance_hash",String.class);verified=row.get("verified_at",OffsetDateTime.class).toInstant();this.evidence=evidence;}
        void requireCurrent(DSLContext sql){check();scope.check(sql);evidence.requireObservation(sql);Record row=sql.fetchOne("select * from legacy_migration_verified_final_acks where decision_id=?",journal.get("decision_id"));if(row==null||!provenance.equals(row.get("provenance_hash",String.class))||!ack.equals(verifiedAck(sql,journal,row)))throw new Failure(Error.UNAVAILABLE);}
        Map<String,Object> acknowledgement(){requireCurrent(scope.sql);return ack;}
        String provenanceHash(){requireCurrent(scope.sql);return provenance;}
        String journalHash(){requireCurrent(scope.sql);return (String)journal.get("journal_hash");}
        String retentionKeyId(){requireCurrent(scope.sql);return (String)journal.get("retention_key_id");}
        Instant verifiedAt(){requireCurrent(scope.sql);return verified;}
        @Override public String toString(){return "VerifiedMigrationFinalAckUse[redacted]";}
    }
    final class FinalDecisionUse {
        private final Scope scope;private final Map<String,Object> journal;
        private final PostgresLegacyMigrationOtpStore.FinalizationSnapshot snapshot;
        private final VerifiedFinalAckUse ack;private final PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence;
        private final UUID command;private final byte[] commandHash;
        private PostgresMigrationBankingEligibilityStore.Promotion promotion;
        private FinalDecisionUse(Scope scope,Map<String,Object> journal,PostgresLegacyMigrationOtpStore.FinalizationSnapshot snapshot,VerifiedFinalAckUse ack,PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence,UUID command,byte[] commandHash){this.scope=scope;this.journal=journal;this.snapshot=snapshot;this.ack=ack;this.evidence=evidence;this.command=command;this.commandHash=commandHash.clone();}
        private void base(DSLContext sql){check();scope.check(sql);evidence.requireObservation(sql);ack.requireCurrent(sql);if(ack.scope!=scope||ack.evidence!=evidence||!journal.equals(ack.journal))throw new Failure(Error.UNAVAILABLE);}
        void requireCurrent(DSLContext sql){base(sql);if(promotion!=null)throw new Failure(Error.UNAVAILABLE);snapshot.requireCurrent(sql);}
        void requireOwnPromotion(DSLContext sql,PostgresMigrationBankingEligibilityStore.Promotion supplied){base(sql);if(promotion==null||promotion!=supplied)throw new Failure(Error.UNAVAILABLE);snapshot.requireOwnPromotion(sql,supplied);}
        private void bind(DSLContext sql,PostgresMigrationBankingEligibilityStore.Promotion supplied){base(sql);if(promotion!=null||supplied==null||!journal.get("decision_id").equals(supplied.sourceId())||!journal.get("identity_id").equals(supplied.identityId())||!journal.get("migration_id").equals(supplied.migrationId())||!"LEGACY_MIGRATION_DUAL_CONTACT".equals(supplied.sourceKind()))throw new Failure(Error.UNAVAILABLE);supplied.requireCurrent(sql);promotion=supplied;requireOwnPromotion(sql,supplied);}
        Map<String,Object> journal(){base(scope.sql);return journal;}
        PostgresLegacyMigrationOtpStore.FinalizationSnapshot snapshot(){base(scope.sql);return snapshot;}
        UUID finalCommandId(){base(scope.sql);return command;}
        byte[] finalCommandHash(){base(scope.sql);return commandHash.clone();}
        @Override public String toString(){return "MigrationFinalDecisionUse[redacted]";}
    }
    @FunctionalInterface interface FinalWork {
        PostgresMigrationBankingEligibilityStore.Promotion apply(DSLContext sql,FinalDecisionUse decision,VerifiedFinalAckUse ack,PostgresLegacyMigrationManagedProofStore.EvidenceUse evidence);
    }
    UUID reserveFinalCommand(PostgresLoginRequestScope.Request owned,UUID migration,String capability){
        check();owned.check();Map<String,Object> original=owned.transaction(sql->{Map<String,Object> j=journal(sql,migration,capability);Record ack=requiredAck(sql,j);verifiedAck(sql,j,ack);return j;});
        UUID replay=owned.transaction(sql->{Record row=sql.fetchOne("select * from legacy_migration_final_commands where decision_id=?",original.get("decision_id"));if(row==null)return null;verifyCommand(sql,original,requiredAck(sql,original),row);return row.get("final_command_id",UUID.class);});
        if(replay!=null){owned.verify();return replay;}
        long started=System.nanoTime();Instant start=owned.transaction(PostgresMigrationFinalAckStore::now);
        UUID result=binding.withEvidence(owned,migration,start,started,evidence->owned.transaction(sql->{
            check();var snapshot=otp.requireFinalization(sql,migration,capability,evidence);snapshot.requireCurrent(sql);Map<String,Object> j=journal(sql,migration,capability);if(!j.equals(original))throw new Failure(Error.CONFLICT);Record ack=requiredAck(sql,j);verifiedAck(sql,j,ack);
            Record old=sql.fetchOne("select * from legacy_migration_final_commands where decision_id=?",j.get("decision_id"));if(old!=null){verifyCommand(sql,j,ack,old);snapshot.requireCurrent(sql);return old.get("final_command_id",UUID.class);}
            UUID command=UUID.randomUUID();long classification=1L;Instant created=normalized(now(sql));var row=new LinkedHashMap<String,Object>();
            row.put("decision_id",j.get("decision_id"));row.put("identity_id",j.get("identity_id"));row.put("migration_id",migration);row.put("final_command_id",command);row.put("actor_id",actor);row.put("retention_key_id",j.get("retention_key_id"));row.put("ack_provenance_hash",ack.get("provenance_hash"));row.put("journal_hash",j.get("journal_hash"));row.put("command_hash",commandHash(j,ack,command,classification));row.put("classification_generation",classification);
            for(String field:List.of("owner_generation","mode_generation","email_generation"))row.put("original_"+field,j.get(field));row.put("original_account_deadline",j.get("original_account_deadline"));row.put("original_intent_deadline",j.get("original_intent_deadline"));row.put("created_at",created);
            if(!created.isBefore((Instant)j.get("original_account_deadline"))||!created.isBefore((Instant)j.get("original_intent_deadline")))throw new Failure(Error.UNAVAILABLE);
            insert(sql,"legacy_migration_final_commands",row);snapshot.requireCurrent(sql);check();return command;
        }));owned.verify();check();return result;
    }
    private static Record requiredAck(DSLContext sql,Map<String,Object> j){Record row=sql.fetchOne("select * from legacy_migration_verified_final_acks where decision_id=?",j.get("decision_id"));if(row==null)throw new Failure(Error.UNAVAILABLE);return row;}
    private byte[] commandHash(Map<String,Object> j,Record ack,UUID command,long classification){return commandHash(crypto,actor,j,ack,command,classification);}
    private static byte[] commandHash(LegacyMigrationManagedProofCrypto crypto,String actor,Map<String,Object> j,Record ack,UUID command,long classification){
        return crypto.hash((String)j.get("retention_key_id"),COMMAND_DOMAIN,"1",actor,scalar(j.get("migration_id")),scalar(j.get("decision_id")),scalar(j.get("identity_id")),command.toString(),scalar(j.get("publication_command_id")),scalar(j.get("journal_hash")),ack.get("final_ack_hash",String.class),ack.get("provenance_hash",String.class),scalar(j.get("receipt_snapshot_hash")),"LEGACY_MIGRATION_DUAL_CONTACT",scalar(j.get("decision_id")),Long.toString(classification),scalar(j.get("owner_generation")),scalar(j.get("mode_generation")),scalar(j.get("email_generation")),scalar(j.get("managed_receipt_id")),scalar(j.get("managed_receipt_hash")),scalar(j.get("target_binding_hash")),scalar(j.get("stage_ack_hash")),scalar(j.get("binding_hash")),scalar(j.get("binding_version")),scalar(j.get("policy_version")));
    }
    private void verifyCommand(DSLContext sql,Map<String,Object> j,Record ack,Record command){verifyCommand(sql,j,ack,command,crypto,actor);}
    private static void verifyCommand(DSLContext sql,Map<String,Object> j,Record ack,Record command,LegacyMigrationManagedProofCrypto crypto,String actor){
        verifiedAck(sql,j,ack,crypto);UUID id=command.get("final_command_id",UUID.class);LegacyMigrationFinalization.id(id);long classification=command.get("classification_generation",Long.class);if(classification!=1L)throw new Failure(Error.UNAVAILABLE);
        for(String field:List.of("decision_id","identity_id","migration_id","retention_key_id","journal_hash"))if(!j.get(field).equals(command.get(field)))throw new Failure(Error.CONFLICT);
        for(String field:List.of("owner_generation","mode_generation","email_generation"))if(!j.get(field).equals(command.get("original_"+field)))throw new Failure(Error.CONFLICT);
        if(!actor.equals(command.get("actor_id"))||!ack.get("provenance_hash").equals(command.get("ack_provenance_hash"))||!MessageDigest.isEqual(commandHash(crypto,actor,j,ack,id,classification),command.get("command_hash",byte[].class)))throw new Failure(Error.UNAVAILABLE);
        Instant created=command.get("created_at",OffsetDateTime.class).toInstant();for(String field:List.of("original_account_deadline","original_intent_deadline")){Instant deadline=command.get(field,OffsetDateTime.class).toInstant();if(!deadline.equals(j.get(field))||!created.isBefore(deadline))throw new Failure(Error.UNAVAILABLE);}
    }
    PostgresMigrationBankingEligibilityStore.Promotion withFinalDecision(PostgresLoginRequestScope.Request owned,UUID migration,String capability,UUID commandId,FinalWork work){
        check();owned.check();LegacyMigrationFinalization.id(commandId);Objects.requireNonNull(work);
        owned.transaction(sql->{journal(sql,migration,capability);return null;});long started=System.nanoTime();Instant start=owned.transaction(PostgresMigrationFinalAckStore::now);
        var result=binding.withEvidence(owned,migration,start,started,evidence->owned.transaction(sql->{
            var snapshot=otp.requireFinalization(sql,migration,capability,evidence);snapshot.requireCurrent(sql);Map<String,Object> j=journal(sql,migration,capability);Record stored=requiredAck(sql,j);verifiedAck(sql,j,stored);
            Record command=sql.fetchOne("select * from legacy_migration_final_commands where decision_id=?",j.get("decision_id"));if(command==null||!commandId.equals(command.get("final_command_id",UUID.class)))throw new Failure(Error.CONFLICT);verifyCommand(sql,j,stored,command);
            if(sql.fetchOne("select decision_id from legacy_migration_final_completions where decision_id=?",j.get("decision_id"))!=null)throw new Failure(Error.CONFLICT);
            Scope scope=new Scope(sql,owned);VerifiedFinalAckUse ack=new VerifiedFinalAckUse(scope,j,stored,evidence);FinalDecisionUse decision=new FinalDecisionUse(scope,j,snapshot,ack,evidence,commandId,command.get("command_hash",byte[].class));decision.requireCurrent(sql);
            var promotion=work.apply(sql,decision,ack,evidence);decision.bind(sql,promotion);consumer.requireOwnPromotion(sql,promotion,decision,ack,evidence);
            if(promotion.classificationGeneration()!=command.get("classification_generation",Long.class)||!promotion.retentionKeyId().equals(j.get("retention_key_id")))throw new Failure(Error.UNAVAILABLE);
            var completion=completion(j,stored,command,promotion,normalized(now(sql)));insert(sql,"legacy_migration_final_completions",completion);
            consumer.requireOwnPromotion(sql,promotion,decision,ack,evidence);decision.requireOwnPromotion(sql,promotion);check();return promotion;
        }));owned.verify();check();return result;
    }
    private Map<String,Object> completion(Map<String,Object> j,Record ack,Record command,PostgresMigrationBankingEligibilityStore.Promotion p,Instant completed){
        var metadata=new LinkedHashMap<String,Object>();metadata.put("decision_id",scalar(j.get("decision_id")));metadata.put("migration_id",scalar(j.get("migration_id")));metadata.put("identity_id",scalar(j.get("identity_id")));metadata.put("state","COMPLETED");String encoded=json(metadata);
        var row=new LinkedHashMap<String,Object>();for(String field:List.of("decision_id","identity_id","migration_id"))row.put(field,j.get(field));row.put("source_kind",p.sourceKind());row.put("source_id",p.sourceId());row.put("classification_generation",p.classificationGeneration());row.put("resulting_owner_generation",p.resultingOwnerGeneration());row.put("resulting_mode_generation",p.resultingModeGeneration());row.put("association_generation",p.associationGeneration());row.put("source_revision",p.sourceRevision());row.put("verification_epoch",p.verificationEpoch());row.put("authority_stamp",p.authorityStamp());row.put("final_ack_hash",ack.get("final_ack_hash"));row.put("receipt_snapshot_hash",j.get("receipt_snapshot_hash"));row.put("journal_hash",j.get("journal_hash"));row.put("retention_key_id",j.get("retention_key_id"));row.put("final_command_id",command.get("final_command_id"));row.put("final_command_hash",command.get("command_hash"));row.put("completed_at",completed);row.put("completion_json",encoded);
        String hash=completionHash(crypto,actor,j,ack,command,row);row.put("provenance_hash",hash);return row;
    }
    private static String completionHash(LegacyMigrationManagedProofCrypto crypto,String actor,Map<String,Object> j,Record ack,Record command,Map<String,Object> row){
        return hex(crypto.hash((String)j.get("retention_key_id"),COMPLETION_DOMAIN,"1",actor,scalar(j.get("journal_hash")),scalar(j.get("decision_id")),scalar(j.get("identity_id")),scalar(j.get("migration_id")),scalar(j.get("publication_command_id")),ack.get("final_ack_hash",String.class),ack.get("provenance_hash",String.class),scalar(j.get("receipt_snapshot_hash")),scalar(row.get("source_kind")),scalar(row.get("source_id")),scalar(row.get("classification_generation")),scalar(row.get("resulting_owner_generation")),scalar(row.get("resulting_mode_generation")),scalar(row.get("association_generation")),scalar(row.get("source_revision")),scalar(row.get("verification_epoch")),hex((byte[])row.get("authority_stamp")),command.get("final_command_id",UUID.class).toString(),hex(command.get("command_hash",byte[].class)),scalar(row.get("completion_json")),LegacyMigrationContact.instant((Instant)row.get("completed_at"))));
    }
    /** Historical completion metadata only; not a source-current or finalization capability. */
    static final class CompletionRead {
        private final Map<String,Object> fields;
        private CompletionRead(Map<String,Object> fields){this.fields=Collections.unmodifiableMap(new LinkedHashMap<>(fields));}
        Map<String,Object> fields(){return fields;}
        @Override public String toString(){return "MigrationCompletionRead[redacted]";}
    }
    static Optional<CompletionRead> validatedCompletion(DSLContext sql,Map<String,Object> j,LegacyMigrationManagedProofCrypto crypto,String actor){
        PostgresVerificationProjectionStore.requireLocked(sql);
        if(!Boolean.TRUE.equals(sql.fetchOne("select to_regclass('legacy_migration_final_completions') is not null").get(0,Boolean.class)))return Optional.empty();
        Record complete=sql.fetchOne("select * from legacy_migration_final_completions where decision_id=?",j.get("decision_id"));if(complete==null)return Optional.empty();
        try{
            if(!actor.equals(j.get("writer_id")))throw new Failure(Error.UNAVAILABLE);Record ack=requiredAck(sql,j);verifiedAck(sql,j,ack,crypto);
            Record command=sql.fetchOne("select * from legacy_migration_final_commands where decision_id=?",j.get("decision_id"));if(command==null)throw new Failure(Error.UNAVAILABLE);verifyCommand(sql,j,ack,command,crypto,actor);
            var row=new LinkedHashMap<String,Object>();for(var field:complete.intoMap().entrySet()){Object value=field.getValue();if(value instanceof OffsetDateTime time)value=time.toInstant();row.put(field.getKey(),value);}
            for(String field:List.of("decision_id","identity_id","migration_id","journal_hash","receipt_snapshot_hash","retention_key_id"))if(!j.get(field).equals(row.get(field)))throw new Failure(Error.UNAVAILABLE);
            if(!"LEGACY_MIGRATION_DUAL_CONTACT".equals(row.get("source_kind"))||!j.get("decision_id").equals(row.get("source_id"))||!ack.get("final_ack_hash").equals(row.get("final_ack_hash"))||!command.get("final_command_id").equals(row.get("final_command_id"))||!MessageDigest.isEqual(command.get("command_hash",byte[].class),(byte[])row.get("final_command_hash")))throw new Failure(Error.UNAVAILABLE);
            for(String field:List.of("classification_generation","source_revision","verification_epoch"))LegacyMigrationFinalization.number(row.get(field),false);
            for(String field:List.of("resulting_owner_generation","resulting_mode_generation","association_generation"))LegacyMigrationFinalization.number(row.get(field),true);
            if(!command.get("classification_generation").equals(row.get("classification_generation"))||!(row.get("authority_stamp") instanceof byte[] stamp)||stamp.length!=32)throw new Failure(Error.UNAVAILABLE);
            Instant completed=(Instant)row.get("completed_at");if(completed.isBefore(command.get("created_at",OffsetDateTime.class).toInstant())||!completed.isBefore((Instant)j.get("original_account_deadline"))||!completed.isBefore((Instant)j.get("original_intent_deadline")))throw new Failure(Error.UNAVAILABLE);
            var metadata=new LinkedHashMap<String,Object>();for(String field:List.of("decision_id","migration_id","identity_id"))metadata.put(field,scalar(j.get(field)));metadata.put("state","COMPLETED");
            if(!json(metadata).equals(row.get("completion_json"))||!completionHash(crypto,actor,j,ack,command,row).equals(row.get("provenance_hash")))throw new Failure(Error.UNAVAILABLE);
            SourceEligibilitySql.requireHistoricalCompletion(sql,complete,j);
            return Optional.of(new CompletionRead(metadata));
        }catch(RuntimeException unavailable){throw new Failure(Error.UNAVAILABLE);}
    }
    /** Strict data decoder only, never an authority/capability factory. */
    static Map<String,Object> validateAck(Map<String,Object> raw,Map<String,Object> journal,Map<String,Object> stage){
        LegacyMigrationFinalization.exact(raw,FINAL);var ordered=new LinkedHashMap<String,Object>();
        for(String k:FINAL){Object v=raw.get(k);if(Set.of("protocol_version","baseline_generation","contact_mutation_revision","publication_revision","email_contact_version","email_receipt_generation","phone_receipt_generation").contains(k))v=LegacyMigrationFinalization.number(v,k.equals("contact_mutation_revision"));ordered.put(k,v);}
        for(String k:List.of("migration_id","decision_id","stage_operation_id","command_id","identity_id","profile_id","email_receipt_id","phone_receipt_id"))LegacyMigrationFinalization.uuid(ordered.get(k));
        for(String k:List.of("baseline_hash","stage_ack_hash","receipt_snapshot_hash","payload_hash","final_ack_hash"))LegacyMigrationFinalization.digest(ordered.get(k));
        if(!Long.valueOf(1).equals(ordered.get("protocol_version"))||!Long.valueOf(1).equals(ordered.get("publication_revision"))||ordered.get("email_receipt_id").equals(ordered.get("phone_receipt_id")))throw new Failure(Error.UNAVAILABLE);
        for(String k:List.of("migration_id","decision_id","stage_operation_id","identity_id","baseline_hash","stage_ack_hash","email_contact_version","email_receipt_id","email_receipt_generation","phone_receipt_id","phone_receipt_generation","receipt_snapshot_hash","payload_hash"))if(!scalar(journal.get(k)).equals(scalar(ordered.get(k))))throw new Failure(Error.CONFLICT);
        if(!scalar(journal.get("publication_command_id")).equals(ordered.get("command_id")))throw new Failure(Error.CONFLICT);
        for(String k:List.of("migration_id","stage_operation_id","identity_id","payload_hash","baseline_hash","stage_ack_hash","email_contact_version","email_digest"))if(!scalar(stage.get(k)).equals(scalar(journal.get(k))))throw new Failure(Error.CONFLICT);
        for(String k:List.of("profile_id","baseline_generation","contact_mutation_revision"))if(!scalar(stage.get(k)).equals(scalar(ordered.get(k))))throw new Failure(Error.CONFLICT);
        if(!LegacyMigrationOtp.hash("armoney-user-migration-final-ack-v1",FINAL.subList(0,FINAL.size()-1),ordered).equals(ordered.get("final_ack_hash")))throw new Failure(Error.UNAVAILABLE);
        return Collections.unmodifiableMap(ordered);
    }
    private static String scalar(Object value){if(value==null)throw new Failure(Error.UNAVAILABLE);return String.valueOf(value);}
    private static String json(Map<String,Object> value){try{String text=JSON.writeValueAsString(value);if(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>12288)throw new Failure(Error.UNAVAILABLE);return text;}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new Failure(Error.UNAVAILABLE);}}
    private static final class Body implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;private boolean ended;
        public CompletionStage<byte[]> getBody(){return result;}
        public synchronized void onSubscribe(Flow.Subscription value){if(ended||subscription!=null){value.cancel();return;}subscription=value;value.request(1);}
        public synchronized void onNext(List<ByteBuffer> values){if(ended)return;for(ByteBuffer value:values){if(value.remaining()>12288-bytes.size()){cancel();return;}byte[] part=new byte[value.remaining()];value.get(part);bytes.writeBytes(part);}if(subscription!=null)subscription.request(1);}
        public synchronized void onError(Throwable ignored){cancel();}
        public synchronized void onComplete(){if(!ended){ended=true;result.complete(bytes.toByteArray());bytes.reset();}}
        synchronized void cancel(){if(!ended){ended=true;if(subscription!=null)subscription.cancel();bytes.reset();result.completeExceptionally(new Failure(Error.UNAVAILABLE));}}
    }
    @Override public void close(){closed=true;pending.forEach(f->f.cancel(true));bodies.forEach(Body::cancel);http.shutdownNow();try{http.awaitTermination(Duration.ofSeconds(2));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
    @Override public String toString(){return "MigrationFinalAckStore[redacted]";}
}
