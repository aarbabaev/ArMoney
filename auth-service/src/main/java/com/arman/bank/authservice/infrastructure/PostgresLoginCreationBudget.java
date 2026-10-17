package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.LoginProof;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;

/** Caller-owned transaction and V→projection→proof controls serialize all new creation. */
final class PostgresLoginCreationBudget {
    private PostgresLoginCreationBudget() {}
    static void reserve(DSLContext sql, UUID id, String kind, Instant now, int limit) {
        PostgresVerificationProjectionStore.requireLocked(sql);
        if (sql.fetchOne("select id from sso_login_creation_reservations where id=?", id) != null)
            throw new LoginProof.Failure(LoginProof.Error.CONFLICT);
        requireAvailable(sql,now,limit);
        sql.execute("insert into sso_login_creation_reservations(id,kind,created_at) values(?,?,cast(? as timestamptz))", id, kind, now.toString());
    }
    static void requireAvailable(DSLContext sql,Instant now,int limit){
        PostgresVerificationProjectionStore.requireLocked(sql);
        // Pre-V12 proofs have no reservation. Reserved proofs count exactly once.
        var count = sql.fetchOne("""
            select (select count(*) from sso_login_creation_reservations where created_at>cast(? as timestamptz))
             +(select count(*) from sso_login_proofs p where p.created_at>cast(? as timestamptz)
               and not exists(select 1 from sso_login_creation_reservations r where r.id=p.id)
               and not exists(select 1 from sso_code_confirmation_results c
                 join sso_code_intents i on i.id=c.intent_id where c.proof_id=p.id and c.budget_origin_id=i.id)) as n
            """, now.minusSeconds(3600).toString(), now.minusSeconds(3600).toString());
        if (count == null || count.get("n", Long.class) >= limit) throw new LoginProof.Failure(LoginProof.Error.RATE_LIMITED);
    }

}
