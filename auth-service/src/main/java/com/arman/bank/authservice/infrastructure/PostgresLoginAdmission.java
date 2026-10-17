package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LoginProof;
import java.math.BigDecimal;
import java.time.*;
import org.jooq.DSLContext;

/** Shared legacy/stage abuse budget. V7's existing mutable writer rendezvous is retained;
 * it is never used as a retained intent payload, binding, authority or actor digest. */
final class PostgresLoginAdmission {
    private PostgresLoginAdmission() {}
    static boolean admit(DSLContext sql,String writer,Instant now){
        PostgresVerificationProjectionStore.requireLocked(sql);
        byte[] hash=LoginProof.writerHash(writer);
        var row=sql.fetchOne("select credits,refilled_at from sso_login_proof_admission where writer_hash=? for update",hash);
        BigDecimal credits=BigDecimal.valueOf(20);
        if(row==null){
            if(sql.fetchOne("select count(*) from sso_login_proof_admission").get(0,Long.class)>=16)return false;
            sql.execute("insert into sso_login_proof_admission values(?,20,cast(? as timestamptz))",hash,now.toString());
        }else{
            Instant refilled=row.get("refilled_at",OffsetDateTime.class).toInstant();
            var elapsed=BigDecimal.valueOf(Math.max(0,Duration.between(refilled,now).toNanos()),9);
            credits=row.get("credits",BigDecimal.class).add(elapsed.multiply(BigDecimal.valueOf(2))).min(credits);
        }
        boolean allowed=credits.compareTo(BigDecimal.ONE)>=0;
        sql.execute("update sso_login_proof_admission set credits=?,refilled_at=cast(? as timestamptz) where writer_hash=?",
            allowed?credits.subtract(BigDecimal.ONE):credits,now.toString(),hash);
        return allowed;
    }
}
