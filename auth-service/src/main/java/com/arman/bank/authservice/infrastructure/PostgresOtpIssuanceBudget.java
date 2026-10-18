package com.arman.bank.authservice.infrastructure;

import org.jooq.DSLContext;
import java.time.*;
import java.util.UUID;

/** Shared registration/migration controls. Reservations and legacy unreserved rows are counted once.
 * Initial lifetime cap has NO pruning/reset path; this is deliberately bounded availability. */
final class PostgresOtpIssuanceBudget {
    static boolean available(DSLContext sql,Instant now){
        PostgresVerificationProjectionStore.requireLocked(sql);
        long lifetime=sql.fetchOne("""
            select (select count(*) from auth_otp_issuance_reservations)
             +(select count(*) from verification_challenges c where not exists(select 1 from auth_otp_issuance_reservations r where r.id=c.id))
             +(select count(*) from legacy_migration_otp_challenges c where not exists(select 1 from auth_otp_issuance_reservations r where r.id=c.id))
            """).get(0,Long.class);
        long hourly=sql.fetchOne("""
            select (select count(*) from auth_otp_issuance_reservations where created_at>cast(? as timestamptz))
             +(select count(*) from verification_challenges c where created_at>cast(? as timestamptz) and not exists(select 1 from auth_otp_issuance_reservations r where r.id=c.id))
             +(select count(*) from legacy_migration_otp_challenges c where created_at>cast(? as timestamptz) and not exists(select 1 from auth_otp_issuance_reservations r where r.id=c.id))
            """,now.minusSeconds(3600).atOffset(ZoneOffset.UTC),now.minusSeconds(3600).atOffset(ZoneOffset.UTC),now.minusSeconds(3600).atOffset(ZoneOffset.UTC)).get(0,Long.class);
        return lifetime<100000&&hourly<1000;
    }
    static void reserve(DSLContext sql,UUID id,String kind,Instant now){
        if(!available(sql,now)||!java.util.Set.of("REGISTRATION","MIGRATION").contains(kind))throw new com.arman.bank.authservice.domain.LegacyMigrationOtp.Failure(com.arman.bank.authservice.domain.LegacyMigrationOtp.Error.RATE_LIMITED);
        sql.execute("insert into auth_otp_issuance_reservations values(?,?,cast(? as timestamptz))",id,kind,now.toString());
    }
}
