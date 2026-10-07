package com.arman.bank.paymentservice.infrastructure;
import com.arman.bank.paymentservice.application.*;
import com.arman.bank.runtime.Database;
import java.util.*;

public final class PostgresEmailOutbox implements EmailOutbox {
    private final Database db;
    public PostgresEmailOutbox(Database db) { this.db = db; }
    @Override public Optional<Claim> claim(String mode) {
        if (!Set.of("sandbox", "sending").contains(mode)) throw new IllegalArgumentException("Invalid email mode");
        return db.transaction(sql -> {
            sql.execute("""
                update email_outbox set status='DEAD',error_code='attempts_exhausted',lease_token=null,lease_until=null,
                updated_at=clock_timestamp() where id in (
                  select id from email_outbox where status='PENDING' and attempts>=8
                  and (lease_until is null or lease_until<=clock_timestamp()) for update skip locked limit 100)
                """);
            var row = sql.fetchOne("""
                select * from email_outbox where status='PENDING' and attempts<8
                and (delivery_mode is null or delivery_mode=?) and next_attempt_at<=clock_timestamp()
                and (lease_until is null or lease_until<=clock_timestamp())
                order by next_attempt_at,created_at,id for update skip locked limit 1
                """, mode);
            if (row == null) return Optional.empty();
            var token = UUID.randomUUID();
            var r = sql.fetchOne("""
                update email_outbox set lease_token=?,lease_until=clock_timestamp()+interval '30 seconds',
                attempts=attempts+1,delivery_mode=coalesce(delivery_mode,?),updated_at=clock_timestamp()
                where id=? returning *
                """, token, mode, row.get("id", UUID.class));
            return Optional.of(new Claim(r.get("id",UUID.class),r.get("owner_id",UUID.class),r.get("payment_id",UUID.class),
                r.get("type",String.class),r.get("amount_minor",Long.class),token,r.get("attempts",Integer.class),
                r.get("recipient_email",String.class),r.get("recipient_verified",Boolean.class)));
        });
    }
    @Override public boolean freezeRecipient(Claim c, EmailDelivery.Recipient recipient) {
        return db.transaction(sql -> sql.execute("""
            update email_outbox set recipient_email=?,recipient_verified=?,updated_at=clock_timestamp()
            where id=? and status='PENDING' and lease_token=? and lease_until>clock_timestamp()
            and recipient_email is null
            """,recipient.email(),recipient.verified(),c.id(),c.token()) == 1);
    }
    @Override public boolean finish(Claim c,String status,String code) {
        if (!Set.of("SENT","SKIPPED","DEAD").contains(status)) throw new IllegalArgumentException("Invalid email status");
        return db.transaction(sql -> sql.execute("""
            update email_outbox set status=?,error_code=?,lease_token=null,lease_until=null,updated_at=clock_timestamp()
            where id=? and status='PENDING' and lease_token=? and lease_until>clock_timestamp()
            """,status,code,c.id(),c.token()) == 1);
    }
    @Override public void retry(Claim c,String code) {
        if (c.attempts()>=8) { finish(c,"DEAD",code); return; }
        int seconds = Math.min(3600, 15 << c.attempts());
        db.transaction(sql -> sql.execute("""
            update email_outbox set error_code=?,lease_token=null,lease_until=null,updated_at=clock_timestamp(),
            next_attempt_at=clock_timestamp()+(? * interval '1 second')
            where id=? and status='PENDING' and lease_token=? and lease_until>clock_timestamp()
            """,code,seconds,c.id(),c.token()));
    }
}
