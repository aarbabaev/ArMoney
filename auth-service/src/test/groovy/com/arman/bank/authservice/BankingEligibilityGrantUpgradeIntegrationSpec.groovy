package com.arman.bank.authservice

import com.arman.bank.authservice.infrastructure.PostgresAuthStore
import com.arman.bank.runtime.Database
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager

class BankingEligibilityGrantUpgradeIntegrationSpec extends Specification {
    def 'populated V10 upgrade preserves 200 owners sessions and history without managed or verification backfill'(){
        given:
        def postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        Flyway.configure().dataSource(postgres.jdbcUrl,postgres.username,postgres.password).locations('classpath:db/migration').target('10').load().migrate()
        def connection=DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        def sql=DSL.using(connection,SQLDialect.POSTGRES)
        def owners=(1..200).collect{UUID.randomUUID()}
        owners.eachWithIndex{UUID id,int i->
            sql.execute('insert into identities(id,email,password_hash) values (?,?,?)',id,'legacy'+i+'@example.test','synthetic-password-digest-'+i)
            sql.execute("insert into sessions(token_hash,identity_id,expires_at) values (?,?,clock_timestamp()+interval '1 hour')",String.format('%064x',i+1),id)
        }
        def identities=sql.fetch('select * from identities order by id').intoMaps()
        def sessions=sql.fetch('select * from sessions order by token_hash').intoMaps()
        def history=sql.fetch('select * from flyway_schema_history order by installed_rank').intoMaps()
        connection.close()
        when:
        def database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        then:
        database.transaction{current->
            assert current.fetch('select * from identities order by id').intoMaps()==identities
            assert current.fetch('select * from sessions order by token_hash').intoMaps()==sessions
            ['legacy_migration_managed_attempts','legacy_migration_managed_claims','legacy_migration_managed_receipts','legacy_migration_managed_targets','legacy_migration_managed_completions','legacy_migration_managed_observations','legacy_migration_account_attempts','legacy_migration_account_claims','legacy_migration_account_receipts','legacy_migration_account_completions','legacy_migration_browser_forms','legacy_migration_browser_accounts','legacy_migration_browser_consumptions'].each { table -> assert current.fetchOne('select count(*) from '+table).get(0,Long)==0L }
            ['legacy_migration_otp_stages','legacy_migration_otp_stage_acks','legacy_migration_otp_candidates','legacy_migration_otp_challenges','legacy_migration_otp_receipts','legacy_migration_otp_outbox','legacy_migration_otp_commands','auth_otp_issuance_reservations'].each { table -> assert current.fetchOne('select count(*) from '+table).get(0,Long)==0L }
            assert current.fetchOne('select count(*) from legacy_migration_intents').get(0,Long)==0L
            assert current.fetch('select * from flyway_schema_history where version not in (?,?,?,?,?,?,?,?,?,?,?,?,?,?) order by installed_rank','11','12','13','14','15','16','17','18','19','20','21','22','23','24').intoMaps()==history
            assert current.fetchOne('select count(*) from banking_eligibility_grants').get(0,Integer)==0
            assert current.fetchOne('select count(*) from managed_registration_principals').get(0,Integer)==0
            assert current.fetchOne('select count(*) from registration_completion_intents').get(0,Integer)==0
            assert current.fetchOne('select count(*) from verification_authorities').get(0,Integer)==0
            true
        }
        owners.every{new PostgresAuthStore(database,false).requireBankingAccess(it);true}
        cleanup:
        database?.close();connection?.close();postgres?.stop()
    }
}
