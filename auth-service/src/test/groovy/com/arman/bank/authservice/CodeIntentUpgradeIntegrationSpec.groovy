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

class CodeIntentUpgradeIntegrationSpec extends Specification {
    def 'V11 to V12 preserves 200 owners sessions every published checksum and no stage backfill'(){
        given:
        def postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        Flyway.configure().dataSource(postgres.jdbcUrl,postgres.username,postgres.password).locations('classpath:db/migration').target('11').load().migrate()
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
        def before=['sso_login_proofs','identities' ,'sessions','external_identities','managed_registration_principals','banking_eligibility_grants'].collectEntries{table->[(table):sql.fetch('select row_to_json(t)::text as snapshot from '+table+' t order by row_to_json(t)::text').getValues('snapshot',String)]}
        def history=sql.fetch('select * from flyway_schema_history order by installed_rank').intoMaps()
        connection.close()
        when: def database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        then:
        database.transaction{current->
            before.each{table,rows->assert current.fetch('select row_to_json(t)::text as snapshot from '+table+' t order by row_to_json(t)::text').getValues('snapshot',String)==rows}
            ['legacy_migration_managed_attempts','legacy_migration_managed_claims','legacy_migration_managed_receipts','legacy_migration_managed_targets','legacy_migration_managed_completions','legacy_migration_managed_observations','legacy_migration_account_attempts','legacy_migration_account_claims','legacy_migration_account_receipts','legacy_migration_account_completions','legacy_migration_browser_forms','legacy_migration_browser_accounts','legacy_migration_browser_consumptions'].each { table -> assert current.fetchOne('select count(*) from '+table).get(0,Long)==0L }
            ['legacy_migration_otp_stages','legacy_migration_otp_stage_acks','legacy_migration_otp_candidates','legacy_migration_otp_challenges','legacy_migration_otp_receipts','legacy_migration_otp_outbox','legacy_migration_otp_commands','auth_otp_issuance_reservations'].each { table -> assert current.fetchOne('select count(*) from '+table).get(0,Long)==0L }
            assert current.fetchOne('select count(*) from legacy_migration_intents').get(0,Long)==0L
            assert current.fetch('select * from flyway_schema_history where version not in (?,?,?,?,?,?,?,?,?,?,?,?,?) order by installed_rank','12','13','14','15','16','17','18','19','20','21','22','23','24').intoMaps()==history
            assert current.fetchOne('select version from flyway_schema_history order by installed_rank desc limit 1').get(0,String)=='24'
            ['sso_code_intents','sso_code_intent_details','sso_code_intent_commands','sso_code_intent_admission','sso_login_creation_reservations','sso_code_intent_encryption_keys','sso_code_intent_nonces'].each{table->assert current.fetchOne('select count(*) from '+table).get(0,Integer)==0}
            true
        }
        owners.every{new PostgresAuthStore(database,false).requireBankingAccess(it);true}
        when:
        def managed=UUID.randomUUID();def enrollment=UUID.randomUUID()
        String issuer='https://managed.example/realm'
        database.transaction{current->
            current.execute('insert into identities(id) values (?)',managed)
            current.execute('insert into external_identities values (?,?,?)',issuer,'managed-subject',managed)
            current.execute("""insert into verification_enrollments(id,issuer,subject,binding_digest,binding_key_id,phone,email,state,created_at,expires_at)
                values (?,?,?,?,'synthetic','+971501234567','synthetic@example.test','PENDING_CONTACTS',clock_timestamp(),clock_timestamp()+interval '1 day')""",enrollment,issuer,'managed-subject',new byte[32])
            current.execute("""insert into verification_authorities(enrollment_id,pending_identity_id,mapped_identity_id,state,created_at,expires_at)
                values (?,?,?,'PROJECTION_PENDING',statement_timestamp(),statement_timestamp()+interval '86400 seconds')""",enrollment,managed,managed)
            current.execute('insert into managed_registration_principals(identity_id,enrollment_id) values (?,?)',managed,enrollment)
        }
        def policy=new LoginProof.Policy([issuer] as Set,['armoney-web'] as Set,['code1'] as Set,['provider'] as Set,100000,100000,1)
        def store=new PostgresCodeIntentStore(database,policy,new CodeIntentRetentionKeys('key',['key':Base64.encoder.encodeToString(new byte[32])]),new CodeIntentDetailCrypto('aead',['aead':Base64.encoder.encodeToString(([7]*32) as byte[])],[] as Set))
        store.cleanup()
        def now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        store.stage('provider',new LoginProof.Write(UUID.randomUUID(),'a'*64,'code1',issuer,'managed-subject','armoney-web','user-session','auth-session',now,now.plusSeconds(120),LoginProof.Kind.INTERACTIVE,UUID.randomUUID(),now))
        then:
        def failure=thrown(LoginProof.Failure)
        failure.error()==LoginProof.Error.RATE_LIMITED
        database.transaction{current->
            assert current.fetchOne('select count(*) from sso_login_creation_reservations').get(0,Integer)==0
            assert current.fetchOne('select count(*) from sso_code_intents').get(0,Integer)==0
            assert current.fetch('select row_to_json(t)::text as snapshot from sso_login_proofs t order by row_to_json(t)::text').getValues('snapshot',String)==before.sso_login_proofs
            true
        }
        cleanup: database?.close();connection?.close();postgres?.stop()
    }
}
