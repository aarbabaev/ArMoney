package com.arman.bank.authservice.infrastructure

import com.arman.bank.runtime.Database
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager

class LegacyMigrationManagedProofUpgradeIntegrationSpec extends Specification {
    def 'V16 to V17 preserves 200 legacy UUIDs sessions counters and full prior migration history with no proof backfill'(){
        given:
        def postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        Flyway.configure().dataSource(postgres.jdbcUrl,postgres.username,postgres.password).locations('classpath:db/migration').target('16').load().migrate()
        def connection=DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        def sql=DSL.using(connection,SQLDialect.POSTGRES)
        (1..200).each{n->
            UUID identityId=UUID.randomUUID()
            sql.execute('insert into identities(id,email,password_hash) values(?,?,?)',identityId,n%2==0?null:'historical'+n+'@example.test',n%2==0?null:'historical-unverified-digest')
            sql.execute("insert into sessions(token_hash,identity_id,expires_at) values(?,?,clock_timestamp()+interval '1 hour')",String.format('%064x',n),identityId)
            if(n%2==0)sql.execute('insert into external_identities(issuer,subject,identity_id) values(?,?,?)','https://issuer.example/sso/realms/armoney','historical-'+n,identityId)
        }
        def tables=['identities','sessions','external_identities','legacy_auth_generations','legacy_auth_association_generations','legacy_auth_session_rows']
        def before=tables.collectEntries{table->[(table):sql.fetch('select row_to_json(t)::text from '+table+' t order by 1').getValues(0,String)]}
        def history=sql.fetch('select * from flyway_schema_history order by installed_rank').intoMaps()
        connection.close()
        when:def database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        then:
        database.transaction{current->
            before.each{table,rows->assert current.fetch('select row_to_json(t)::text from '+table+' t order by 1').getValues(0,String)==rows}
            assert current.fetch('select * from flyway_schema_history where version not in (?,?,?,?,?,?) order by installed_rank','17','18','19','20','21','22').intoMaps()==history
            assert current.fetchOne('select count(*) from identities').get(0,Long)==200L
            assert current.fetchOne('select count(*) from sessions').get(0,Long)==200L
            assert current.fetchOne('select count(*) from identities where password_hash is null').get(0,Long)==100L
            assert current.fetchOne('select count(*) from legacy_migration_intents').get(0,Long)==0L
            ['legacy_migration_managed_attempts','legacy_migration_managed_claims','legacy_migration_managed_receipts','legacy_migration_managed_targets','legacy_migration_managed_completions','legacy_migration_managed_observations','legacy_migration_account_attempts','legacy_migration_account_claims','legacy_migration_account_receipts','legacy_migration_account_completions','legacy_migration_browser_forms','legacy_migration_browser_accounts','legacy_migration_browser_consumptions'].each{table->assert current.fetchOne('select count(*) from '+table).get(0,Long)==0L}
            assert current.fetchOne('select count(*) from managed_registration_principals').get(0,Long)==0L
            true
        }
        cleanup:database?.close();connection?.close();postgres?.stop()
    }
}
