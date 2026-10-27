package com.arman.bank.authservice

import com.arman.bank.runtime.Database
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager

class LegacyMigrationUpgradeIntegrationSpec extends Specification {
    def 'V14 to V15 preserves 200 old owners credentials exact mappings and sessions and introduces only unverified zero counters'(){
        given:
        def postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        Flyway.configure().dataSource(postgres.jdbcUrl,postgres.username,postgres.password).locations('classpath:db/migration').target('14').load().migrate()
        def connection=DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        def sql=DSL.using(connection,SQLDialect.POSTGRES)
        (1..200).each{n->
            UUID owner=UUID.randomUUID()
            sql.execute('insert into identities(id,email,password_hash) values(?,?,?)',owner,n==200?null:'owner'+n+'@example.test',n==200?null:'synthetic-verifier-'+n)
            sql.execute("insert into sessions(token_hash,identity_id,expires_at) values(?,?,clock_timestamp()+interval '1 hour')",String.format('%064x',n),owner)
            sql.execute('insert into external_identities(issuer,subject,identity_id) values(?,?,?)','https://legacy.example/realm','subject-'+n,owner)
        }
        def history=sql.fetch('select * from flyway_schema_history order by installed_rank').intoMaps()
        def snapshots=['identities','sessions','external_identities','managed_registration_principals','banking_eligibility_grants'].collectEntries{table->[(table):sql.fetch('select row_to_json(t)::text from '+table+' t order by 1').getValues(0,String)]}
        connection.close()
        when:def database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        then:
        database.transaction{current->
            ['legacy_migration_managed_attempts','legacy_migration_managed_claims','legacy_migration_managed_receipts','legacy_migration_managed_targets','legacy_migration_managed_completions','legacy_migration_managed_observations','legacy_migration_account_attempts','legacy_migration_account_claims','legacy_migration_account_receipts','legacy_migration_account_completions','legacy_migration_browser_forms','legacy_migration_browser_accounts','legacy_migration_browser_consumptions'].each { table -> assert current.fetchOne('select count(*) from '+table).get(0,Long)==0L }
            ['legacy_migration_otp_stages','legacy_migration_otp_stage_acks','legacy_migration_otp_candidates','legacy_migration_otp_challenges','legacy_migration_otp_receipts','legacy_migration_otp_outbox','legacy_migration_otp_commands','auth_otp_issuance_reservations'].each { table -> assert current.fetchOne('select count(*) from '+table).get(0,Long)==0L }
            assert current.fetch('select * from flyway_schema_history where version not in (?,?,?,?,?,?,?,?,?) order by installed_rank','15','16','17','18','19','20','21','22','23').intoMaps()==history
            snapshots.each{table,rows->assert current.fetch('select row_to_json(t)::text from '+table+' t order by 1').getValues(0,String)==rows}
            assert current.fetchOne('select count(*) from legacy_auth_generations where owner_generation=0 and credential_generation=0 and email_generation=0').get(0,Long)==200L
            assert current.fetchOne('select count(*) from legacy_auth_session_rows').get(0,Long)==200L
            assert current.fetchOne('select count(*) from legacy_auth_association_generations where generation=0 and active=true').get(0,Long)==200L
            ['legacy_migration_intents','legacy_migration_continuity','legacy_migration_associations','legacy_migration_pkce_custody'].each{table->assert current.fetchOne('select count(*) from '+table).get(0,Long)==0L}
            true
        }
        cleanup:database?.close();connection?.close();postgres?.stop()
    }
}
