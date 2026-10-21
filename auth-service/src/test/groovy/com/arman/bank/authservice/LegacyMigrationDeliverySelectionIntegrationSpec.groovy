package com.arman.bank.authservice.infrastructure

import com.arman.bank.runtime.Database
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager

/** Exact production routing SQL against private PG TEMP technical queue probes.
 * NOT full delivery-claim/binding/User-ACK acceptance. No permanent authority/receipt is seeded. */
class LegacyMigrationDeliverySelectionIntegrationSpec extends Specification {
    def 'unavailable earlier migration never selects evidence ahead of another ready migration'(){
        given:
        def postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        def database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        def connection=DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        def sql=DSL.using(connection,SQLDialect.POSTGRES)
        // Session-private shadow tables deliberately have NO authority/FK payload. Routing helper only.
        sql.execute('create temporary table legacy_migration_otp_challenges(id uuid primary key,state text not null)')
        sql.execute('create temporary table legacy_migration_intents(id uuid primary key,authorization_deadline timestamptz not null)')
        sql.execute('create temporary table legacy_migration_otp_stages(migration_id uuid primary key,intent_deadline timestamptz not null)')
        sql.execute('create temporary table legacy_migration_managed_receipts(migration_id uuid primary key,account_deadline timestamptz not null,attempt_deadline timestamptz not null,token_expires_at timestamptz not null)')
        sql.execute('create temporary table legacy_migration_otp_outbox(id uuid primary key,migration_id uuid not null,challenge_id uuid not null,state text not null,attempts integer not null,available_at timestamptz not null,lease_until timestamptz,expires_at timestamptz not null)')
        UUID migrationA=UUID.randomUUID()
        UUID migrationB=UUID.randomUUID()
        UUID deliveryA=UUID.randomUUID()
        UUID deliveryB=UUID.randomUUID()
        UUID challengeA=UUID.randomUUID()
        UUID challengeB=UUID.randomUUID()
        [migrationA,migrationB].each{migrationId->
            sql.execute("insert into legacy_migration_intents values(?,clock_timestamp()+interval '120 seconds')",migrationId)
            sql.execute("insert into legacy_migration_otp_stages values(?,clock_timestamp()+interval '900 seconds')",migrationId)
            sql.execute("insert into legacy_migration_managed_receipts values(?,clock_timestamp()+interval '120 seconds',clock_timestamp()+interval '120 seconds',clock_timestamp()+interval '60 seconds')",migrationId)
        }
        if(accountExpired)sql.execute("update legacy_migration_intents set authorization_deadline=clock_timestamp()-interval '1 second' where id=?",migrationA)
        sql.execute('insert into legacy_migration_otp_challenges values(?,?)',challengeA,'PENDING_DELIVERY')
        sql.execute('insert into legacy_migration_otp_challenges values(?,?)',challengeB,'PENDING_DELIVERY')
        sql.execute("insert into legacy_migration_otp_outbox values(?,?,?,?,0,clock_timestamp()-interval '1 hour',clock_timestamp()+interval '30 seconds',clock_timestamp()+interval '5 minutes')",deliveryA,migrationA,challengeA,earlierState)
        if(earlierState=='RETRY_WAIT')sql.execute("update legacy_migration_otp_outbox set available_at=clock_timestamp()+interval '1 minute' where id=?",deliveryA)
        if(expired)sql.execute("update legacy_migration_otp_outbox set expires_at=clock_timestamp()-interval '1 second' where id=?",deliveryA)
        sql.execute("insert into legacy_migration_otp_outbox values(?,?,?,'PENDING',0,clock_timestamp()-interval '1 second',null,clock_timestamp()+interval '5 minutes')",deliveryB,migrationB,challengeB)
        when:def selected=PostgresLegacyMigrationOtpStore.eligibleDelivery(sql)
        then:
        selected.deliveryId()==deliveryB
        selected.migrationId()==migrationB
        sql.fetchOne('select count(*) from public.legacy_migration_otp_outbox').get(0,Long)==0L
        sql.fetchOne('select count(*) from public.legacy_migration_otp_receipts').get(0,Long)==0L
        when:sql.execute("update legacy_migration_otp_outbox set state='LEASED',lease_until=clock_timestamp()+interval '30 seconds' where id=?",deliveryB)
        then:PostgresLegacyMigrationOtpStore.eligibleDelivery(sql)==null
        cleanup:connection?.close();database?.close();postgres?.stop()
        where:
        earlierState | expired | accountExpired
        'LEASED'     | false   | false
        'RETRY_WAIT' | false   | false
        'PENDING'    | true    | false
        'PENDING'    | false   | true
    }
}
