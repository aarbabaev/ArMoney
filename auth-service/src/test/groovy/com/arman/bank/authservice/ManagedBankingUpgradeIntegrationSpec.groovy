package com.arman.bank.authservice

import com.arman.bank.authservice.infrastructure.PostgresAuthStore
import com.arman.bank.runtime.Database
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager

class ManagedBankingUpgradeIntegrationSpec extends Specification {
    def 'V13 to V14 preserves 200 existing owners sessions and all published checksums without managed grants or session backfill'(){
        given:
        def postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        Flyway.configure().dataSource(postgres.jdbcUrl,postgres.username,postgres.password).locations('classpath:db/migration').target('13').load().migrate()
        def connection=DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        def sql=DSL.using(connection,SQLDialect.POSTGRES)
        def owners=(1..200).collect{UUID.randomUUID()}
        owners.eachWithIndex{UUID id,int n->
            sql.execute('insert into identities(id,email,password_hash) values(?,?,?)',id,'owner'+n+'@example.test','synthetic-password-digest')
            sql.execute("insert into sessions(token_hash,identity_id,expires_at) values(?,?,clock_timestamp()+interval '1 hour')",String.format('%064x',n+1),id)
        }
        def history=sql.fetch('select * from flyway_schema_history order by installed_rank').intoMaps()
        def before=['identities','sessions','external_identities','managed_registration_principals','banking_eligibility_grants','sso_login_proofs','sso_login_events','sso_code_confirmation_results'].collectEntries{table->
            [(table):sql.fetch('select row_to_json(t)::text from '+table+' t order by 1').getValues(0,String)]
        }
        connection.close()
        when:def database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        then:
        database.transaction{current->
            ['legacy_migration_managed_attempts','legacy_migration_managed_claims','legacy_migration_managed_receipts','legacy_migration_managed_targets','legacy_migration_managed_completions','legacy_migration_managed_observations','legacy_migration_account_attempts','legacy_migration_account_claims','legacy_migration_account_receipts','legacy_migration_account_completions','legacy_migration_browser_forms','legacy_migration_browser_accounts','legacy_migration_browser_consumptions'].each { table -> assert current.fetchOne('select count(*) from '+table).get(0,Long)==0L }
            ['legacy_migration_otp_stages','legacy_migration_otp_stage_acks','legacy_migration_otp_candidates','legacy_migration_otp_challenges','legacy_migration_otp_receipts','legacy_migration_otp_outbox','legacy_migration_otp_commands','auth_otp_issuance_reservations'].each { table -> assert current.fetchOne('select count(*) from '+table).get(0,Long)==0L }
            assert current.fetchOne('select count(*) from legacy_migration_intents').get(0,Long)==0L
            assert current.fetch('select * from flyway_schema_history where version not in (?,?,?,?,?,?,?,?,?,?) order by installed_rank','14','15','16','17','18','19','20','21','22','23').intoMaps()==history
            before.each{table,rows->assert current.fetch('select row_to_json(t)::text from '+table+' t order by 1').getValues(0,String)==rows}
            assert current.fetchOne('select version from flyway_schema_history order by installed_rank desc limit 1').get(0,String)=='23'
            ['managed_banking_operations','managed_banking_sessions','managed_banking_event_consumptions','managed_banking_completions','managed_banking_cleanup'].each{table->assert current.fetchOne('select count(*) from '+table).get(0,Long)==0L}
            true
        }
        owners.every{new PostgresAuthStore(database,false).requireBankingAccess(it);true}
        cleanup:database?.close();connection?.close();postgres?.stop()
    }
}
