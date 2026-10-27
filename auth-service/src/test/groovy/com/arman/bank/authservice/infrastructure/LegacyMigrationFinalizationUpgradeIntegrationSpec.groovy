package com.arman.bank.authservice.infrastructure

import com.arman.bank.runtime.Database
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager

/** Real isolated upgrade only; historical rows do not seed positive migration evidence. */
class LegacyMigrationFinalizationUpgradeIntegrationSpec extends Specification {
    def 'V18 to V19 preserves prior history and 200 existing UUIDs credentials sessions and zero generations'(){
        given:
        def postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        Flyway.configure().dataSource(postgres.jdbcUrl,postgres.username,postgres.password).locations('classpath:db/migration').target('18').load().migrate()
        def connection=DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        def sql=DSL.using(connection,SQLDialect.POSTGRES)
        (1..200).each{n->
            UUID owner=UUID.randomUUID()
            sql.execute('insert into identities(id,email,password_hash) values(?,?,?)',owner,n%2==0?null:'historical'+n+'@example.test',n%2==0?null:'historical-unverified-digest')
            sql.execute("insert into sessions(token_hash,identity_id,expires_at) values(?,?,clock_timestamp()+interval '1 hour')",String.format('%064x',n),owner)
            if(n%2==0)sql.execute('insert into external_identities(issuer,subject,identity_id) values(?,?,?)','https://issuer.example/sso/realms/armoney','historical-'+n,owner)
        }
        def tables=['identities','sessions','external_identities','legacy_auth_generations','legacy_auth_association_generations','legacy_auth_session_rows']
        def before=tables.collectEntries{table->[(table):sql.fetch('select row_to_json(t)::text from '+table+' t order by 1').getValues(0,String)]}
        def history=sql.fetch('select * from flyway_schema_history order by installed_rank').intoMaps()
        connection.close()
        when:def database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        then:
        database.transaction{current->
            before.each{table,rows->assert current.fetch('select row_to_json(t)::text from '+table+' t order by 1').getValues(0,String)==rows}
            assert current.fetch('select * from flyway_schema_history where version not in (?,?,?,?,?) order by installed_rank','19','20','21','22','23').intoMaps()==history
            assert current.fetchOne('select count(*) from identities').get(0,Long)==200L
            assert current.fetchOne('select count(*) from sessions').get(0,Long)==200L
            assert current.fetchOne('select count(*) from identities where password_hash is null and email is null').get(0,Long)==100L
            assert current.fetchOne('select count(*) from legacy_migration_publication_decisions').get(0,Long)==0L
            assert current.fetchOne('select count(*) from managed_registration_principals').get(0,Long)==0L
            assert current.fetchOne('select count(*) from legacy_auth_generations where owner_generation=0 and credential_generation=0 and email_generation=0').get(0,Long)==200L
            true
        }
        cleanup:database?.close();connection?.close();postgres?.stop()
    }
}
