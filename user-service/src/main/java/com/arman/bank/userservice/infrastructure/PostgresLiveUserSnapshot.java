package com.arman.bank.userservice.infrastructure;

import com.arman.bank.userservice.domain.*;
import com.arman.bank.userservice.application.LiveUserCurrentStore.Rejected;
import org.jooq.DSLContext;
import org.jooq.Record;
import java.util.*;

/** Caller-owned central transaction only. Immutable placement is admitted centrally
 * before every supported shard write, so absent directory cannot hide an admitted
 * shard creation. Direct orphan SQL is not a supported placement operation.
 */
final class PostgresLiveUserSnapshot {
    private PostgresLiveUserSnapshot(){}
    record Snapshot(Map<String,Object> baseline,Map<String,Object> before){}
    static void lock(DSLContext sql){sql.fetch("select pg_advisory_xact_lock(725631904128::bigint)");}
    static boolean hasHistory(DSLContext sql,UUID owner){return sql.fetchOne("select migration_id from migration_baselines where identity_id=? limit 1",owner)!=null;}
    static Snapshot read(DSLContext sql,UUID owner,boolean bootstrap) {
        PostgresMigrationBaselineStore.requireUnreserved(sql,owner);
        Record claim=sql.fetchOne("select * from registration_phone_claims where identity_id=? for update",owner);
        Record directory=sql.fetchOne("select * from profile_directory where identity_id=? for update",owner);
        Record projection=sql.fetchOne("select * from verification_projection_current where identity_id=? for update",owner);
        Record counter=sql.fetchOne("select * from migration_contact_counters where identity_id=? for update",owner);
        var histories=sql.fetch("select * from migration_baselines where identity_id=? order by migration_id for update",owner);
        if(claim==null&&directory==null&&projection==null) {
            if(!bootstrap||counter!=null||!histories.isEmpty()||sql.fetchOne("select id from profiles where identity_id=?",owner)!=null) throw new Rejected(409);
            return new Snapshot(null,null);
        }
        if(claim==null||directory==null||!Boolean.TRUE.equals(directory.get("initialized",Boolean.class))) throw new Rejected(409);
        if(!Objects.equals(claim.get("phone_number"),directory.get("phone_number"))||
            (projection!=null&&!Objects.equals(claim.get("phone_number"),projection.get("canonical_phone")))) throw new Rejected(409);
        if("primary".equals(directory.get("shard_id"))) {
            Record physical=sql.fetchOne("select id from profiles where identity_id=?",owner);
            if(physical==null||!Objects.equals(physical.get("id"),directory.get("profile_id")))throw new Rejected(409);
        }
        Map<String,Object> baseline=null;
        var released=histories.stream().filter(r->"RELEASED_AFTER_COMPLETION".equals(r.get("state",String.class))).toList();
        if(released.size()>1)throw new Rejected(503);
        if(!released.isEmpty()) {
            var row=released.get(0);
            var command=LiveUserCurrentRoutes.parse(row.get("publication_command").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var ack=LiveUserCurrentRoutes.parse(row.get("publication_ack").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            MigrationBaseline.tuple(command);MigrationBaseline.exact(ack,MigrationBaseline.FINAL);
            for(String k:List.of("migration_id","decision_id","stage_operation_id","command_id","payload_hash"))if(!Objects.equals(command.get(k),ack.get(k)))throw new Rejected(409);
            if(!owner.toString().equals(ack.get("identity_id"))||!row.get("migration_id").toString().equals(ack.get("migration_id"))||
                !MigrationBaseline.hash("armoney-user-migration-final-ack-v1",MigrationBaseline.FINAL.subList(0,MigrationBaseline.FINAL.size()-1),ack).equals(ack.get("final_ack_hash")))throw new Rejected(409);
            var b=new LinkedHashMap<String,Object>();for(String k:LiveUserRevision.BASELINE)b.put(k,ack.get(k));baseline=LiveUserRevision.baseline(b);
        }
        var u=new LinkedHashMap<String,Object>();u.put("profile_id",directory.get("profile_id").toString());u.put("shard_id",directory.get("shard_id"));
        u.put("baseline_generation",counter==null?0L:counter.get("generation",Long.class));u.put("contact_mutation_revision",counter==null?0L:counter.get("revision",Long.class));
        u.put("projection_present",projection!=null);u.put("projection_revision",projection==null?null:projection.get("version",Integer.class));
        u.put("phone_contact_version",projection==null?null:projection.get("phone_contact_version",Integer.class));u.put("verification_epoch",projection==null?null:projection.get("verification_epoch",Long.class));
        return new Snapshot(baseline,LiveUserRevision.user(u));
    }
}
