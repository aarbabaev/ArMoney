package com.arman.bank.authservice

import com.arman.bank.authservice.infrastructure.VerificationProjectionConfiguration
import com.arman.bank.runtime.Database
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.exception.DataAccessException
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager

/** Published V7 -> current V9 on synthetic PostgreSQL. Historical rows are fixture data,
 * not proof of provider delivery, ACTIVE authority, banking access or activation.
 */
class VerificationProjectionUpgradeIntegrationSpec extends Specification {
    def 'V8 and V9 preserve populated V7 identity receipt session and proof rows and introduce empty disabled tracker'() {
        given:
        def postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations('classpath:db/migration').target('7').load().migrate()
        def connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        def sql = DSL.using(connection, SQLDialect.POSTGRES)
        UUID identity = UUID.randomUUID()
        UUID external = UUID.randomUUID()
        UUID enrollment = UUID.randomUUID()
        sql.execute('''insert into identities(id,email,password_hash,registration_phone,notification_email,notification_email_verified)
            values (?,?,?,?,?,true)''', identity, 'legacy@example.test', 'synthetic-password-digest', '+971501234567', 'verified@example.test')
        sql.execute('''insert into identities(id,email,password_hash,notification_email,notification_email_verified)
            values (?,null,null,?,false)''', external, 'pending@example.test')
        sql.execute('insert into external_identities(issuer,subject,identity_id) values (?,?,?)', 'https://issuer.example/realm', 'synthetic-existing-subject', external)
        [('a' * 64):identity, ('b' * 64):external].each { digest, id ->
            sql.execute("insert into sessions(token_hash,identity_id,expires_at) values (?,?,statement_timestamp()+interval '1 hour')", digest, id)
        }
        sql.execute('insert into auth_attempts(subject_hash,window_start,attempts) values (?,statement_timestamp(),3)', 'c' * 64)
        sql.execute('''insert into verification_enrollments(id,issuer,subject,binding_digest,binding_key_id,phone,email,state,created_at,expires_at)
            values (?,?,?,?,?,?,?,'VERIFIED',statement_timestamp(),statement_timestamp()+interval '1 hour')''', enrollment,
            'https://issuer.example/realm', 'synthetic-enrollment', new byte[32], 'test', '+971521234567', 'receipt@example.test')
        ['REGISTRATION_EMAIL','REGISTRATION_PHONE'].each { purpose ->
            UUID challenge = UUID.randomUUID()
            sql.execute('''insert into verification_challenges(id,enrollment_id,purpose,contact_version,state,digest_key_id,created_at,expires_at,resend_at)
                values (?,?,?,1,'CONSUMED','test',statement_timestamp(),statement_timestamp()+interval '5 minutes',statement_timestamp())''', challenge, enrollment, purpose)
            sql.execute('''insert into verification_receipts(id,enrollment_id,challenge_id,purpose,contact_version,created_at)
                values (?,?,?,?,1,statement_timestamp())''', UUID.randomUUID(), enrollment, challenge, purpose)
            sql.execute('''insert into verification_delivery_outbox(id,challenge_id,enrollment_id,purpose,contact_version,state,key_id,available_at,expires_at)
                values (?,?,?,?,1,'ACCEPTED','test',statement_timestamp(),statement_timestamp()+interval '5 minutes')''', UUID.randomUUID(), challenge, enrollment, purpose)
        }
        sql.execute('''insert into verification_commands(requester_digest,command_id,hash_key_id,payload_hash,result,created_at,expires_at)
            values (?,?,'test',?,'synthetic-historical-result',statement_timestamp(),statement_timestamp()+interval '1 hour')''', new byte[32], UUID.randomUUID(), new byte[32])
        UUID proof = UUID.randomUUID()
        sql.execute('''insert into sso_login_proofs(id,issuer,code_digest,code_key_id,binding_hash,payload_hash,kind,event_id,login_notice_eligible,
            issued_at,expires_at,created_at,retained_until) values (?,?,?,'test',?,?,'reuse',null,false,
            statement_timestamp(),statement_timestamp()+interval '120 seconds',statement_timestamp(),statement_timestamp()+interval '604800 seconds')''',
            proof, 'https://issuer.example/realm', 'd' * 64, new byte[32], new byte[32])
        sql.execute('''insert into sso_login_proof_commands(requester_hash,command_id,payload_hash,proof_id,created_at,retained_until)
            values (?,?,?,?,statement_timestamp(),statement_timestamp()+interval '604800 seconds')''', new byte[32], UUID.randomUUID(), new byte[32], proof)
        sql.execute("insert into sso_login_proof_admission(writer_hash,credits,refilled_at) values (decode(?,'hex'),7,statement_timestamp())", '0' * 64)
        def before = snapshots(sql)
        def history = sql.fetch('select * from flyway_schema_history order by installed_rank').intoMaps()
        connection.close()

        when:
        def database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        then:
        database.transaction { current ->
            ['legacy_migration_managed_attempts','legacy_migration_managed_claims','legacy_migration_managed_receipts','legacy_migration_managed_targets','legacy_migration_managed_completions','legacy_migration_managed_observations','legacy_migration_account_attempts','legacy_migration_account_claims','legacy_migration_account_receipts','legacy_migration_account_completions','legacy_migration_browser_forms','legacy_migration_browser_accounts','legacy_migration_browser_consumptions'].each { table -> assert current.fetchOne('select count(*) from '+table).get(0,Long)==0L }
            ['legacy_migration_otp_stages','legacy_migration_otp_stage_acks','legacy_migration_otp_candidates','legacy_migration_otp_challenges','legacy_migration_otp_receipts','legacy_migration_otp_outbox','legacy_migration_otp_commands','auth_otp_issuance_reservations'].each { table -> assert current.fetchOne('select count(*) from '+table).get(0,Long)==0L }
            assert current.fetchOne('select count(*) from legacy_migration_intents').get(0,Long)==0L
            assert current.fetchOne('select version from flyway_schema_history order by installed_rank desc limit 1').get('version') == '23'
            ['verification_authorities','verification_candidates','verification_authority_phone_claims','verification_flow_barriers','verification_flows','verification_authority_admission','verification_authority_commands'].each { table ->
                assert current.fetchOne('select count(*) as n from ' + table).get('n', Long) == 0
            }
            assert current.fetch('select * from verification_authority_config').intoMaps() == [[id: 1, retention_key_fingerprint: null]]
            ['verification_challenges','verification_receipts'].each { table ->
                assert current.fetchOne('select count(*) as n from ' + table + ' where candidate_id is not null').get('n', Long) == 0
            }
            assert current.fetch('select * from flyway_schema_history where cast(version as integer) <= ? order by installed_rank', 7).intoMaps() == history
            assert current.fetch('select version from flyway_schema_history order by installed_rank').getValues('version', String) == ['1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18','19','20','21','22','23']
            assert snapshots(current) == before
            ['auth_verification_projection_commands','auth_verification_projection_tombstones','auth_verification_projection_admission'].each { table ->
                assert current.fetchOne('select count(*) as n from ' + table).get('n', Long) == 0
            }
            def control = current.fetchOne('select * from auth_verification_projection_control')
            assert control.get('id', Integer) == 1
            assert control.get('retention_key_fingerprint') == null
            true
        }
        def disabled = new VerificationProjectionConfiguration([:])
        !disabled.enabled
        disabled.store(database) == null
        disabled.worker(database) == null

        when: 'the old enrollment constraint must continue to reject fabricated ACTIVE state'
        database.transaction { it.execute("update verification_enrollments set state='ACTIVE' where id=?", enrollment) }
        then:
        def rejected = thrown(DataAccessException)
        rejected.sqlState() == '23514'
        database.transaction { snapshots(it) } == before

        when: 'migration validation and a new connection pool must preserve the same old records'
        database.close()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        then:
        database.ready()
        database.transaction { snapshots(it) } == before
        cleanup:
        connection?.close(); database?.close(); postgres?.stop()
    }

    private static Map snapshots(def sql) {
        // Preserve every published column; only V9 candidate_id is excluded and asserted null above.
        [identities:'id',external_identities:'issuer,subject',sessions:'token_hash',auth_attempts:'subject_hash',
         verification_control:'id',verification_enrollments:'id',verification_challenges:'id',verification_receipts:'id',
         verification_delivery_outbox:'id',verification_commands:'requester_digest,command_id',sso_login_proof_control:'id',
         sso_login_proof_admission:'writer_hash',sso_login_events:'issuer,event_id',sso_login_proofs:'id',
         sso_login_proof_commands:'requester_hash,command_id'].collectEntries { table, order ->
            [(table):sql.fetch('select ' + (table in ['verification_challenges','verification_receipts'] ? "(to_jsonb(t) - 'candidate_id')::text" : 'row_to_json(t)::text') + ' as snapshot from ' + table + ' t order by ' + order).getValues('snapshot', String)]
        }
    }
}
