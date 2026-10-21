package com.arman.bank.userservice.infrastructure;
import com.arman.bank.runtime.Database;
import com.arman.bank.userservice.application.LiveUserCurrentStore;
import com.arman.bank.userservice.domain.LiveUserRevision;
import java.util.*;
public final class PostgresLiveUserCurrentStore implements LiveUserCurrentStore {
    private final Database database;
    public PostgresLiveUserCurrentStore(Database database){this.database=Objects.requireNonNull(database);}
    @Override public Map<String,Object> current(String writer,Map<String,Object> input){
        var request=LiveUserRevision.currentRequest(writer,input);
        try{return database.transaction(sql->{
            PostgresLiveUserSnapshot.lock(sql);var snapshot=PostgresLiveUserSnapshot.read(sql,LiveUserRevision.uuid(request.get("identity_id")),false);
            if(snapshot.baseline()==null||snapshot.before()==null)throw new Rejected(409);
            for(String k:List.of("migration_id","decision_id"))if(!Objects.equals(snapshot.baseline().get(k),request.get(k)))throw new Rejected(409);
            var result=new LinkedHashMap<String,Object>();for(String k:List.of("protocol_version","operation_id","identity_id","payload_hash","migration_id","decision_id","classification_generation","auth_epoch","auth_source_revision"))result.put(k,request.get(k));
            result.put("status","CURRENT");result.put("current",snapshot.before());
            result.put("observed_at_us",sql.fetchOne("select floor(extract(epoch from clock_timestamp())*1000000)::bigint").get(0,Long.class));
            return LiveUserRevision.immutable(result);
        });}catch(com.arman.bank.userservice.application.MigrationBaselineStore.Rejected e){throw new Rejected(e.status()==503?503:409);}
        catch(org.jooq.exception.DataAccessException e){throw new Rejected(503);}
    }
}
