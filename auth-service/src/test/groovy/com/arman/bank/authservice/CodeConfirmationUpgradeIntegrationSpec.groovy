package com.arman.bank.authservice

import com.arman.bank.authservice.infrastructure.*
import com.arman.bank.authservice.domain.LoginProof
import java.time.Instant
import com.arman.bank.runtime.Database
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager

class CodeConfirmationUpgradeIntegrationSpec extends Specification {
    def 'V12 to V13 preserves 200 owners sessions staged records and every published checksum without confirmation backfill'(){
        given:
        def postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        Flyway.configure().dataSource(postgres.jdbcUrl,postgres.username,postgres.password).locations('classpath:db/migration').target('12').load().migrate()
        def connection=DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password)
        def sql=DSL.using(connection,SQLDialect.POSTGRES)
        def owners=(1..200).collect{UUID.randomUUID()}
        owners.eachWithIndex{UUID id,int index->
            sql.execute('insert into identities(id,email,password_hash) values (?,?,?)',id,'legacy'+index+'@example.test','synthetic-digest-'+index)
            sql.execute("insert into sessions(token_hash,identity_id,expires_at) values (?,?,clock_timestamp()+interval '1 hour')",String.format('%064x',index+1),id)
        }
        sql.execute("""insert into sso_login_proofs values (?,? ,?,'legacy-code',?,?, 'reuse',null,false,
            statement_timestamp(),statement_timestamp()+interval '120 seconds',statement_timestamp(),statement_timestamp()+interval '7 days')""",
            UUID.randomUUID(),'https://legacy.example/realm','e'*64,new byte[32],new byte[32])
        sql.execute("""insert into sso_code_intents values (gen_random_uuid(),repeat('a',64),'code1','retained',
            decode(repeat('00',32),'hex'),decode(repeat('01',32),'hex'),decode(repeat('02',32),'hex'),decode(repeat('03',32),'hex'),
            statement_timestamp(),statement_timestamp()+interval '120 seconds',statement_timestamp(),statement_timestamp()+interval '30 seconds',statement_timestamp()+interval '7 days 120 seconds')""")
        def before=['sso_login_proofs','identities' ,'sessions','external_identities','managed_registration_principals','banking_eligibility_grants','sso_code_intents','sso_code_intent_cleanup','sso_login_creation_reservations'].collectEntries{table->[(table):sql.fetch('select row_to_json(t)::text as snapshot from '+table+' t order by row_to_json(t)::text').getValues('snapshot',String)]}
        def history=sql.fetch('select * from flyway_schema_history order by installed_rank').intoMaps()
        connection.close()
        when: def database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        then:
        database.transaction{current->
            before.each{table,rows->assert current.fetch('select row_to_json(t)::text as snapshot from '+table+' t order by row_to_json(t)::text').getValues('snapshot',String)==rows}
            assert current.fetch('select * from flyway_schema_history where version<>? order by installed_rank','13').intoMaps()==history
            assert current.fetchOne('select version from flyway_schema_history order by installed_rank desc limit 1').get(0,String)=='13'
            ['sso_code_confirmation_anchors','sso_code_confirmation_results','sso_code_confirmation_anchor_completions'].each{table->assert current.fetchOne('select count(*) from '+table).get(0,Integer)==0}
            true
        }
        owners.every{new PostgresAuthStore(database,false).requireBankingAccess(it);true}
        cleanup: database?.close();connection?.close();postgres?.stop()
    }
}
