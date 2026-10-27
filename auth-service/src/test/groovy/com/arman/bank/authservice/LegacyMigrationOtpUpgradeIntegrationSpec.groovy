package com.arman.bank.authservice.infrastructure

import com.arman.bank.runtime.Database
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager
import java.time.OffsetDateTime

class LegacyMigrationOtpUpgradeIntegrationSpec extends Specification {
    def 'V15 to V16 preserves 200 owners and unverified legacy challenge while counting it exactly once without backfill'(){
        given:
        def postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        Flyway.configure().dataSource(postgres.jdbcUrl,postgres.username,postgres.password).locations('classpath:db/migration').target('15').load().migrate()
        def connection=DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        def sql=DSL.using(connection,SQLDialect.POSTGRES)
        (1..200).each{n->UUID id=UUID.randomUUID();sql.execute('insert into identities(id,email,password_hash) values(?,?,?)',id,'owner'+n+'@example.test','synthetic-password-digest');sql.execute("insert into sessions(token_hash,identity_id,expires_at) values(?,?,clock_timestamp()+interval '1 hour')",String.format('%064x',n),id)}
        // Actual pre-V16 unverified/locked legacy row: no accepted delivery, known OTP or possession receipt.
        UUID enrollment=UUID.randomUUID()
        sql.execute("""insert into verification_enrollments(id,issuer,subject,binding_digest,binding_key_id,phone,email,state,created_at,expires_at)
            values(?,'https://issuer.example/realm','legacy-unverified',decode(repeat('00',32),'hex'),'old','+971541234567','legacy-unverified@example.test','PENDING_CONTACTS',clock_timestamp(),clock_timestamp()+interval '1 day')""",enrollment)
        sql.execute("""insert into verification_challenges(id,enrollment_id,purpose,contact_version,state,digest_key_id,created_at,expires_at,resend_at)
            values(gen_random_uuid(),?,'REGISTRATION_EMAIL',1,'LOCKED','old',clock_timestamp(),clock_timestamp()+interval '5 minutes',clock_timestamp()+interval '60 seconds')""",enrollment)
        def history=sql.fetch('select * from flyway_schema_history order by installed_rank').intoMaps()
        def tables=['identities','sessions','external_identities','legacy_auth_generations','legacy_auth_session_rows','legacy_migration_intents','verification_enrollments','verification_challenges','verification_receipts']
        def before=tables.collectEntries{table->[(table):sql.fetch('select row_to_json(t)::text from '+table+' t order by 1').getValues(0,String)]}
        connection.close()
        when:def database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        then:database.transaction{current->
            assert current.fetch('select * from flyway_schema_history where version not in (?,?,?,?,?,?,?,?) order by installed_rank','16','17','18','19','20','21','22','23').intoMaps()==history
            before.each{table,rows->assert current.fetch('select row_to_json(t)::text from '+table+' t order by 1').getValues(0,String)==rows}
            ['legacy_migration_managed_attempts','legacy_migration_managed_claims','legacy_migration_managed_receipts','legacy_migration_managed_targets','legacy_migration_managed_completions','legacy_migration_managed_observations','legacy_migration_account_attempts','legacy_migration_account_claims','legacy_migration_account_receipts','legacy_migration_account_completions','legacy_migration_browser_forms','legacy_migration_browser_accounts','legacy_migration_browser_consumptions'].each { table -> assert current.fetchOne('select count(*) from '+table).get(0,Long)==0L }
            ['legacy_migration_otp_stages','legacy_migration_otp_stage_acks','legacy_migration_otp_candidates','legacy_migration_otp_challenges','legacy_migration_otp_receipts','legacy_migration_otp_outbox','legacy_migration_otp_commands','auth_otp_issuance_reservations'].each{table->assert current.fetchOne('select count(*) from '+table).get(0,Long)==0L}
            true
        }
        when:database.transaction{current->current.execute("insert into auth_otp_issuance_reservations select gen_random_uuid(),'MIGRATION',clock_timestamp() from generate_series(1,998)")}
        then:database.transaction{current->PostgresVerificationProjectionStore.lockControls(current);PostgresOtpIssuanceBudget.available(current,current.fetchOne('select clock_timestamp()').get(0,OffsetDateTime).toInstant())}
        when:database.transaction{current->current.execute("insert into auth_otp_issuance_reservations values(gen_random_uuid(),'MIGRATION',clock_timestamp())")}
        then:!database.transaction{current->PostgresVerificationProjectionStore.lockControls(current);PostgresOtpIssuanceBudget.available(current,current.fetchOne('select clock_timestamp()').get(0,OffsetDateTime).toInstant())}
        cleanup:database?.close();connection?.close();postgres?.stop()
    }
}
