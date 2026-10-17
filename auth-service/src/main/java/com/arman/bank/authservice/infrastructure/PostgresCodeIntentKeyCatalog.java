package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LoginProof;
import java.security.MessageDigest;
import java.util.*;
import org.jooq.DSLContext;

/** Caller-owned controls/catalog locks only; never acquires a connection or commits. */
public final class PostgresCodeIntentKeyCatalog {
    private final CodeIntentDetailCrypto crypto;
    public PostgresCodeIntentKeyCatalog(CodeIntentDetailCrypto crypto){this.crypto=Objects.requireNonNull(crypto);}
    public record Reservation(String keyId,long generation,byte[] nonce){
        public Reservation{nonce=nonce.clone();}
        @Override public byte[] nonce(){return nonce.clone();}
        @Override public String toString(){return "CodeIntentNonceReservation[redacted]";}
    }
    public void synchronize(DSLContext sql,boolean requireKeys){
        PostgresVerificationProjectionStore.requireLocked(sql);
        var rows=sql.fetch("select * from sso_code_intent_encryption_keys order by key_id for update");
        if(rows.size()>1024)fail();
        for(String id:crypto.keyIds()){
            byte[] fingerprint=crypto.fingerprint(id);
            var row=sql.fetchOne("select * from sso_code_intent_encryption_keys where key_id=?",id);
            if(row==null){
                if(sql.fetchOne("select count(*) from sso_code_intent_encryption_keys").get(0,Long.class)>=1024
                    ||sql.fetchOne("select key_id from sso_code_intent_encryption_keys where material_fingerprint=?",fingerprint)!=null)fail();
                sql.execute("insert into sso_code_intent_encryption_keys(key_id,material_fingerprint,state,generation) values(?,?,'ACTIVE',1)",id,fingerprint);
            }else if(!MessageDigest.isEqual(row.get("material_fingerprint",byte[].class),fingerprint))fail();
        }
        for(String id:new TreeSet<>(crypto.retiredIds())){
            var row=sql.fetchOne("select state from sso_code_intent_encryption_keys where key_id=?",id);
            if(row==null)fail();
            if("ACTIVE".equals(row.get("state",String.class)))sql.execute("update sso_code_intent_encryption_keys set state='RETIRED',generation=generation+1 where key_id=?",id);
        }
        if(requireKeys){
            for(var row:sql.fetch("select distinct encryption_key_id from sso_code_intent_details"))if(!crypto.contains(row.get(0,String.class)))fail();
            requireActive(sql,crypto.currentId(),null);
        }
    }
    /** Returned only after the caller has acknowledged COMMIT may this reservation be encrypted. */
    public Reservation reserve(DSLContext sql){
        PostgresVerificationProjectionStore.requireLocked(sql);
        long generation=requireActive(sql,crypto.currentId(),null);
        if(sql.fetchOne("select count(*) from sso_code_intent_nonces where encryption_key_id=?",crypto.currentId()).get(0,Long.class)>=100_000)fail();
        for(int attempt=0;attempt<3;attempt++){
            byte[] nonce=crypto.nonce();
            if(sql.execute("insert into sso_code_intent_nonces(encryption_key_id,nonce) values(?,?) on conflict do nothing",crypto.currentId(),nonce)==1)
                return new Reservation(crypto.currentId(),generation,nonce);
        }
        fail();return null;
    }
    public long requireActive(DSLContext sql,String id,Long expected){
        PostgresVerificationProjectionStore.requireLocked(sql);
        var row=sql.fetchOne("select state,generation from sso_code_intent_encryption_keys where key_id=? for update",id);
        if(row==null||!"ACTIVE".equals(row.get("state",String.class))||(expected!=null&&!expected.equals(row.get("generation",Long.class))))fail();
        return row.get("generation",Long.class);
    }
    public void fence(DSLContext sql,Reservation reservation){
        requireActive(sql,reservation.keyId(),reservation.generation());
        if(sql.fetchOne("select nonce from sso_code_intent_nonces where encryption_key_id=? and nonce=?",reservation.keyId(),reservation.nonce())==null)fail();
    }
    public int cleanup(DSLContext sql){
        PostgresVerificationProjectionStore.requireLocked(sql);
        return sql.execute("""
            delete from sso_code_intent_nonces n where (n.encryption_key_id,n.nonce) in
              (select n.encryption_key_id,n.nonce from sso_code_intent_nonces n
               join sso_code_intent_encryption_keys k on k.key_id=n.encryption_key_id
               where k.state='RETIRED' and not exists(select 1 from sso_code_intent_details d where d.encryption_key_id=k.key_id)
               order by n.encryption_key_id,n.nonce limit 100 for update of n)
            """);
    }
    private static void fail(){throw new LoginProof.Failure(LoginProof.Error.UNAVAILABLE);}
}
