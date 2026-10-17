package com.arman.bank.authservice

import com.arman.bank.runtime.Database
import com.arman.bank.authservice.domain.VerificationProjection
import com.arman.bank.authservice.infrastructure.HttpVerificationProjection
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.exception.DataAccessException
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager

/** Populated published V8 -> V9, synthetic data only. Existing receipt/proof/command
 * rows are historical fixtures, never evidence of ACTIVE or provider acceptance.
 */
class IdentityVerificationUpgradeIntegrationSpec extends Specification {
    def 'V9 preserves populated V8 credentials sessions receipts proofs and pending projections without authority backfill'() {
        given:
        def postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations('classpath:db/migration').target('8').load().migrate()
        def connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        def sql = DSL.using(connection, SQLDialect.POSTGRES)
        UUID identity = UUID.randomUUID()
        UUID enrollment = UUID.randomUUID()
        UUID emailReceipt = UUID.randomUUID()
        UUID phoneReceipt = UUID.randomUUID()
        sql.execute('''insert into identities(id,email,password_hash,registration_phone,notification_email,notification_email_verified)
            values (?,?,?,?,?,true)''', identity, 'legacy@example.test', 'synthetic-password-digest', '+971501234567', 'verified@example.test')
        sql.execute('insert into external_identities(issuer,subject,identity_id) values (?,?,?)', 'https://issuer.example/realm', 'synthetic-mapped-subject', identity)
        sql.execute("insert into sessions(token_hash,identity_id,expires_at) values (?,?,statement_timestamp()+interval '1 hour')", 'a' * 64, identity)
        sql.execute('insert into auth_attempts(subject_hash,window_start,attempts) values (?,statement_timestamp(),3)', 'b' * 64)
        sql.execute('''insert into verification_enrollments(id,issuer,subject,binding_digest,binding_key_id,phone,email,state,created_at,expires_at)
            values (?,?,?,?,?,?,?,'VERIFIED',statement_timestamp(),statement_timestamp()+interval '24 hours')''', enrollment,
            'https://issuer.example/realm', 'synthetic-mapped-subject', new byte[32], 'test', '+971501234567', 'verified@example.test')
        [[purpose:'REGISTRATION_EMAIL',receipt:emailReceipt],[purpose:'REGISTRATION_PHONE',receipt:phoneReceipt]].each { item ->
            UUID challenge = UUID.randomUUID()
            sql.execute('''insert into verification_challenges(id,enrollment_id,purpose,contact_version,state,digest_key_id,created_at,expires_at,resend_at)
                values (?,?,?,1,'CONSUMED','test',statement_timestamp(),statement_timestamp()+interval '5 minutes',statement_timestamp())''', challenge, enrollment, item.purpose)
            sql.execute('''insert into verification_receipts(id,enrollment_id,challenge_id,purpose,contact_version,created_at)
                values (?,?,?,?,1,statement_timestamp())''', item.receipt, enrollment, challenge, item.purpose)
            sql.execute('''insert into verification_delivery_outbox(id,challenge_id,enrollment_id,purpose,contact_version,state,key_id,available_at,expires_at)
                values (?,?,?,?,1,'ACCEPTED','test',statement_timestamp(),statement_timestamp()+interval '5 minutes')''', UUID.randomUUID(), challenge, enrollment, item.purpose)
        }
        sql.execute('''insert into verification_commands(requester_digest,command_id,hash_key_id,payload_hash,result,created_at,expires_at)
            values (?,?,'test',?,'synthetic-historical-result',statement_timestamp(),statement_timestamp()+interval '1 hour')''', new byte[32], UUID.randomUUID(), new byte[32])
        UUID proof = UUID.randomUUID()
        sql.execute('''insert into sso_login_proofs(id,issuer,code_digest,code_key_id,binding_hash,payload_hash,kind,event_id,login_notice_eligible,
            issued_at,expires_at,created_at,retained_until) values (?,?,?,'test',?,?,'reuse',null,false,
            statement_timestamp(),statement_timestamp()+interval '120 seconds',statement_timestamp(),statement_timestamp()+interval '604800 seconds')''',
            proof, 'https://issuer.example/realm', 'c' * 64, new byte[32], new byte[32])
        sql.execute('''insert into sso_login_proof_commands(requester_hash,command_id,payload_hash,proof_id,created_at,retained_until)
            values (?,?,?,?,statement_timestamp(),statement_timestamp()+interval '604800 seconds')''', new byte[32], UUID.randomUUID(), new byte[32], proof)
        UUID projection = UUID.randomUUID()
        def projectionCommand = VerificationProjection.create(projection, identity, 0, 1, '+971501234567', 1, 1)
        sql.execute('''insert into auth_verification_projection_tombstones(writer_hash,operation_id,payload_hash,identity_digest,
            version,phone_contact_version,verification_epoch,state,recovery_barrier,created_at,expires_at)
            values (?,?,?, ?,1,1,1,'PENDING',true,statement_timestamp(),statement_timestamp()+interval '604800 seconds')''',
            new byte[32], projection, projectionCommand.payloadHash(), new byte[32])
        sql.execute('''insert into auth_verification_projection_commands(writer_hash,operation_id,identity_id,enrollment_id,
            expected_version,version,canonical_phone,phone_contact_version,email_contact_version,verification_epoch,
            email_receipt_id,phone_receipt_id,payload_hash,request_body,available_at,created_at,expires_at)
            values (?,?,?,?,0,1,?,1,1,1,?,?,?, ?,statement_timestamp(),statement_timestamp(),statement_timestamp()+interval '604800 seconds')''',
            new byte[32], projection, identity, enrollment, '+971501234567', emailReceipt, phoneReceipt, projectionCommand.payloadHash(),
            HttpVerificationProjection.body(projectionCommand))
        def before = snapshots(sql)
        def history = sql.fetch('select * from flyway_schema_history order by installed_rank').intoMaps()
        connection.close()

        when:
        def database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        then:
        database.transaction { current ->
            assert current.fetchOne('select version from flyway_schema_history order by installed_rank desc limit 1').get('version') == '13'
            assert current.fetch('select * from flyway_schema_history where version not in (?,?,?,?,?) order by installed_rank', '9', '10', '11', '12', '13').intoMaps() == history
            assert snapshots(current) == before
            ['verification_authorities','verification_candidates','verification_authority_phone_claims','verification_flows','verification_flow_barriers',
             'verification_authority_admission','verification_authority_commands'].each { table ->
                assert current.fetchOne('select count(*) as n from ' + table).get('n', Long) == 0
            }
            assert current.fetchOne('select retention_key_fingerprint from verification_authority_config where id=1').get('retention_key_fingerprint') == null
            assert current.fetchOne('select count(*) as n from verification_challenges where candidate_id is not null').get('n', Long) == 0
            assert current.fetchOne('select count(*) as n from verification_receipts where candidate_id is not null').get('n', Long) == 0
            true
        }

        when: 'published enrollment states must still deny fabricated ACTIVE'
        database.transaction { it.execute("update verification_enrollments set state='ACTIVE' where id=?", enrollment) }
        then:
        def rejected = thrown(DataAccessException)
        rejected.sqlState() == '23514'
        database.transaction { snapshots(it) } == before

        when: 'restart validates migration checksums without materializing authority or changing pending recovery metadata'
        database.close()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        then:
        database.ready()
        database.transaction { snapshots(it) } == before
        database.transaction { it.fetchOne('select count(*) as n from verification_authorities').get('n', Long) } == 0
        cleanup:
        connection?.close(); database?.close(); postgres?.stop()
    }

    private static Map snapshots(def sql) {
        [identities:'id',external_identities:'issuer,subject',sessions:'token_hash',auth_attempts:'subject_hash',
         verification_control:'id',verification_enrollments:'id',verification_phone_claims:'phone',verification_challenges:'id',
         verification_receipts:'id',verification_delivery_outbox:'id',verification_commands:'requester_digest,command_id',
         sso_login_proof_control:'id',sso_login_proof_admission:'writer_hash',sso_login_events:'issuer,event_id',
         sso_login_proofs:'id',sso_login_proof_commands:'requester_hash,command_id',auth_verification_projection_control:'id',
         auth_verification_projection_admission:'writer_hash',auth_verification_projection_tombstones:'writer_hash,operation_id',
         auth_verification_projection_commands:'writer_hash,operation_id'].collectEntries { table, order ->
            // V9 appends a nullable candidate_id; compare ALL prior columns and assert the new columns separately above.
            String expression = table in ['verification_challenges','verification_receipts'] ? "(to_jsonb(t)-'candidate_id')::text" : 'to_jsonb(t)::text'
            [(table):sql.fetch('select ' + expression + ' as snapshot from ' + table + ' t order by ' + order).getValues('snapshot', String)]
        }
    }
}
