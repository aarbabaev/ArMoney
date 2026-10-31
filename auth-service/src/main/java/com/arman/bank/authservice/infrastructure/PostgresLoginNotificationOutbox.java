package com.arman.bank.authservice.infrastructure;
import com.arman.bank.runtime.Database;
import com.arman.bank.authservice.application.NotificationDelivery.Result;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;
import org.jooq.DSLContext;
import org.jooq.Record;
/** Own DB only. No transport/profile operation runs in these transactions. */
public final class PostgresLoginNotificationOutbox {
    public record Claim(UUID id,UUID lease,UUID owner,String recipient,Instant occurredAt,int attempts,boolean profileFrozen,String displayName){
        @Override public String toString(){return "LoginNoticeClaim[redacted]";}
    }
    private final Database database;private final LoginNotificationCrypto crypto;private final LegacyMigrationManagedProofCrypto migration;
    public PostgresLoginNotificationOutbox(Database db,ManagedBankingKeys keys,LegacyMigrationManagedProofCrypto migration){database=Objects.requireNonNull(db);crypto=new LoginNotificationCrypto(keys);this.migration=migration;}
    static void enqueue(DSLContext sql,ManagedBankingKeys keys,String issuer,UUID event,UUID owner,String subject,String recipient,byte[] stamp,Record proof,Record persisted){
        if(!"interactive".equals(proof.get("kind"))||!Boolean.TRUE.equals(proof.get("login_notice_eligible",Boolean.class))||!owner.equals(persisted.get("identity_id",UUID.class))||!Objects.equals(recipient,persisted.get("verified_email"))||!EmailConfiguration.validEmail(recipient)||!now(sql).isBefore(time(persisted,"recipient_expires_at")))throw new IllegalStateException("Login notice evidence unavailable");
        UUID id=UUID.randomUUID();String key=keys.currentId();Instant created=now(sql),occurred=time(persisted,"password_authenticated_at");
        var context=new LoginNotificationCrypto.Context(id,issuer,event,owner,key,stamp,occurred);
        var encrypted=new LoginNotificationCrypto(keys).encrypt(context,new LoginNotificationCrypto.Payload(subject,recipient,null));
        sql.execute("""
            insert into login_notification_outbox(id,issuer,event_id,identity_id,key_id,authority_stamp,occurred_at,created_at,expires_at,tombstone_until,state,next_attempt_at,capture_nonce,nonce,ciphertext)
            values(?,?,?,?,?,?,cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz),'PENDING',cast(? as timestamptz),?,?,?)
            """,id,issuer,event,owner,key,stamp,occurred.toString(),created.toString(),Collections.min(List.of(created.plusSeconds(86400),time(persisted,"recipient_expires_at"))).toString(),created.plusSeconds(604800).toString(),created.toString(),encrypted.nonce(),encrypted.nonce(),encrypted.ciphertext());
    }
    public Optional<Claim> claim(){return database.transaction(sql->{
        PostgresVerificationProjectionStore.lockControls(sql);
        // Terminal payload is erased immediately; expired pending payload is also retired without sending.
        sql.execute("update login_notification_outbox set state='EXPIRED',ciphertext=null,lease_id=null,leased_until=null where id in (select id from login_notification_outbox where state in ('PENDING','LEASED') and expires_at<=clock_timestamp() order by expires_at limit 100)");
        sql.execute("delete from login_notification_outbox where id in (select id from login_notification_outbox where tombstone_until<=clock_timestamp() and state in ('SENT','DEAD','EXPIRED','SKIPPED') order by tombstone_until limit 100)");
        Record row=sql.fetchOne("select * from login_notification_outbox where ((state='PENDING' and next_attempt_at<=clock_timestamp()) or (state='LEASED' and leased_until<=clock_timestamp())) and expires_at>clock_timestamp() order by next_attempt_at,id for update skip locked limit 1");
        if(row==null)return Optional.empty();
        var payload=eligible(sql,row);if(payload==null||row.get("attempts",Integer.class)>=5){retire(sql,row,"SKIPPED");return Optional.empty();}
        UUID lease=UUID.randomUUID();sql.execute("update login_notification_outbox set state='LEASED',lease_id=?,leased_until=clock_timestamp()+interval '60 seconds',attempts=attempts+1 where id=?",lease,row.get("id"));
        return Optional.of(new Claim(row.get("id",UUID.class),lease,row.get("identity_id",UUID.class),payload.recipient(),time(row,"occurred_at"),row.get("attempts",Integer.class)+1,row.get("profile_frozen",Boolean.class),payload.displayName()));
    });}
    /** Freeze the optional presentation once, and recheck current authority immediately before SMTP. */
    public Optional<Claim> prepareSend(Claim claim,String displayName){return database.transaction(sql->{
        PostgresVerificationProjectionStore.lockControls(sql);Record row=leased(sql,claim);if(row==null)return Optional.empty();
        var payload=eligible(sql,row);if(payload==null){retire(sql,row,"SKIPPED");return Optional.empty();}
        String name=payload.displayName();
        if(!row.get("profile_frozen",Boolean.class)){
            name=LoginEmail.name(displayName);var encrypted=crypto.encrypt(context(row),new LoginNotificationCrypto.Payload(payload.subject(),payload.recipient(),name));
            sql.execute("update login_notification_outbox set profile_frozen=true,nonce=?,ciphertext=? where id=? and lease_id=?",encrypted.nonce(),encrypted.ciphertext(),claim.id(),claim.lease());
        }
        return Optional.of(new Claim(claim.id(),claim.lease(),claim.owner(),payload.recipient(),claim.occurredAt(),claim.attempts(),true,name));
    });}
    public boolean finish(Claim claim,Result result){return database.transaction(sql->{
        Record row=leased(sql,claim);if(row==null)return false;
        String state=result==Result.ACCEPTED?"SENT":result==Result.PERMANENT_FAILURE||row.get("attempts",Integer.class)>=5?"DEAD":"PENDING";
        if(!state.equals("PENDING")){retire(sql,row,state);return true;}
        long delay=Math.min(900,5L << Math.min(7,row.get("attempts",Integer.class)-1));
        sql.execute("update login_notification_outbox set state='PENDING',lease_id=null,leased_until=null,next_attempt_at=clock_timestamp()+(? * interval '1 second') where id=? and lease_id=?",delay,claim.id(),claim.lease());return true;
    });}
    private Record leased(DSLContext sql,Claim c){return sql.fetchOne("select * from login_notification_outbox where id=? and state='LEASED' and lease_id=? and leased_until>clock_timestamp() and expires_at>clock_timestamp() for update",c.id(),c.lease());}
    private LoginNotificationCrypto.Payload eligible(DSLContext sql,Record row){
        try{
            var payload=crypto.decrypt(context(row),row.get("nonce",byte[].class),row.get("ciphertext",byte[].class));
            var current=new PostgresBankingEligibilityStore(sql,migration).requireManagedLocal(row.get("issuer",String.class),payload.subject());
            if(!row.get("identity_id",UUID.class).equals(current.identity())||!payload.recipient().equals(current.email())||!MessageDigest.isEqual(row.get("authority_stamp",byte[].class),current.stamp())
                ||sql.fetchOne("select token_hash from managed_banking_sessions where issuer=? and event_id=? and identity_id=? and expires_at>clock_timestamp()",row.get("issuer"),row.get("event_id"),row.get("identity_id"))==null)return null;
            return payload;
        }catch(org.jooq.exception.DataAccessException failure){throw failure;}
        catch(RuntimeException rejected){return null;}
    }
    private static void retire(DSLContext sql,Record row,String state){sql.execute("update login_notification_outbox set state=?,ciphertext=null,lease_id=null,leased_until=null where id=?",state,row.get("id"));}
    private static LoginNotificationCrypto.Context context(Record r){return new LoginNotificationCrypto.Context(r.get("id",UUID.class),r.get("issuer",String.class),r.get("event_id",UUID.class),r.get("identity_id",UUID.class),r.get("key_id",String.class),r.get("authority_stamp",byte[].class),time(r,"occurred_at"));}
    private static Instant time(Record r,String name){return r.get(name,OffsetDateTime.class).toInstant();}
    private static Instant now(DSLContext sql){return sql.fetchOne("select clock_timestamp()").get(0,OffsetDateTime.class).toInstant();}
}
