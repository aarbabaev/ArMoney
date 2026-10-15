package com.arman.bank.authservice

import com.arman.bank.authservice.infrastructure.PostgresAuthStore
import com.arman.bank.runtime.Database
import org.flywaydb.core.Flyway
import org.jooq.exception.DataAccessException
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager
import java.time.Instant

/** Explicit populated V5 -> current V7 upgrade on synthetic PostgreSQL only.
 * No receipts, identities, provider state or ACTIVE eligibility are invented.
 */
class VerificationV5UpgradeIntegrationSpec extends Specification {
    def 'V6 and V7 preserve populated V5 credentials sessions verified contacts mappings and legacy constraints'() {
        given:
        def postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations('classpath:db/migration').target('5').load().migrate()
        def connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        def sql = org.jooq.impl.DSL.using(connection, org.jooq.SQLDialect.POSTGRES)
        assert sql.fetchOne('select version from flyway_schema_history order by installed_rank desc limit 1').get('version') == '5'
        UUID passwordOwner = UUID.randomUUID(), providerOwner = UUID.randomUUID()
        String digest = 'synthetic-preexisting-password-digest'
        sql.execute('''insert into identities(id,email,password_hash,registration_phone,notification_email,notification_email_verified,created_at)
            values (?,?,?,?,?,true,timestamptz '2026-01-01T00:00:00Z')''', passwordOwner,
            'legacy-login@example.test', digest, '+971501234567', 'verified-contact@example.test')
        sql.execute('''insert into identities(id,email,password_hash,registration_phone,notification_email,notification_email_verified,created_at)
            values (?,null,null,?,?,false,timestamptz '2026-01-02T00:00:00Z')''', providerOwner,
            '+971521234567', 'pending-contact@example.test')
        sql.execute('insert into external_identities(issuer,subject,identity_id) values (?,?,?)',
            'https://issuer.example/realm', 'synthetic-existing-subject', providerOwner)
        [('a' * 64): passwordOwner, ('b' * 64): providerOwner].each { token, owner ->
            sql.execute('''insert into sessions(token_hash,identity_id,created_at,expires_at)
                values (?,?,timestamptz '2026-01-01T00:00:00Z',timestamptz '2037-01-01T00:00:00Z')''', token, owner)
        }
        sql.execute("insert into auth_attempts(subject_hash,window_start,attempts) values (?,timestamptz '2026-01-01T00:00:00Z',3)", 'c' * 64)
        def before = [identities: rows(sql, 'identities', 'id'), sessions: rows(sql, 'sessions', 'token_hash'),
            external: rows(sql, 'external_identities', 'issuer,subject'), attempts: rows(sql, 'auth_attempts', 'subject_hash')]
        def history = sql.fetch('select version,script,checksum from flyway_schema_history order by installed_rank').intoMaps()
        connection.close()

        when:
        def database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        def store = new PostgresAuthStore(database)

        then:
        database.transaction { current ->
            assert current.fetchOne('select version from flyway_schema_history order by installed_rank desc limit 1').get('version') == '7'
            assert current.fetch('select version,script,checksum from flyway_schema_history where version not in (?, ?) order by installed_rank', '6', '7').intoMaps() == history
            assert current.fetch('select version from flyway_schema_history order by installed_rank').getValues('version', String) == ['1','2','3','4','5','6','7']
            assert rows(current, 'identities', 'id') == before.identities
            assert rows(current, 'sessions', 'token_hash') == before.sessions
            assert rows(current, 'external_identities', 'issuer,subject') == before.external
            assert rows(current, 'auth_attempts', 'subject_hash') == before.attempts
            ['verification_enrollments','verification_phone_claims','verification_challenges','verification_receipts',
             'verification_delivery_outbox','verification_commands', 'sso_login_proof_admission',
             'sso_login_events','sso_login_proofs','sso_login_proof_commands'].each { table ->
                assert current.fetchOne('select count(*) as n from ' + table).get('n', Long) == 0
            }
            assert current.fetchOne('select count(*) as n from verification_control where id=1').get('n', Long) == 1
            assert current.fetchOne('select count(*) as n from sso_login_proof_control where id=1').get('n', Long) == 1
            true
        }
        store.findSession('a' * 64, Instant.now()).orElseThrow().id() == passwordOwner
        store.findSession('b' * 64, Instant.now()).orElseThrow().id() == providerOwner
        store.findByEmail('legacy-login@example.test').orElseThrow().passwordHash() == digest
        store.emailContact(passwordOwner).orElseThrow().email() == 'verified-contact@example.test'
        store.emailContact(passwordOwner).orElseThrow().verified()
        store.emailContact(providerOwner).orElseThrow().email() == 'pending-contact@example.test'
        !store.emailContact(providerOwner).orElseThrow().verified()

        when: 'V6 must not allow a direct ACTIVE enrollment without an implemented authority'
        database.transaction { current -> current.execute('''insert into verification_enrollments
            (id,issuer,subject,binding_digest,binding_key_id,phone,email,state,created_at,expires_at)
            values (?,?,?,?,?,?,?,'ACTIVE',clock_timestamp(),clock_timestamp()+interval '1 hour')''',
            UUID.randomUUID(), 'https://issuer.example/realm', 'synthetic-pending', new byte[32],
            'test', '+971561234567', 'candidate@example.test') }
        then:
        def activeRejected = thrown(DataAccessException)
        activeRejected.sqlState() == '23514'
        database.transaction { it.fetchOne('select count(*) as n from verification_enrollments').get('n', Long) } == 0

        when: 'published verified-contact constraint must survive the upgrade'
        database.transaction { it.execute('update identities set notification_email=null where id=?', passwordOwner) }
        then:
        def contactRejected = thrown(DataAccessException)
        contactRejected.sqlState() == '23514'
        database.transaction { rows(it, 'identities', 'id') } == before.identities

        when: 'restarting migration validation must not alter preserved rows'
        database.close()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        then:
        database.ready()
        database.transaction { rows(it, 'sessions', 'token_hash') } == before.sessions
        database.transaction { rows(it, 'identities', 'id') } == before.identities

        cleanup:
        connection?.close(); database?.close(); postgres?.stop()
    }

    private static List<String> rows(def sql, String table, String ordering) {
        // All identifiers come exclusively from the fixed test cases above.
        sql.fetch('select row_to_json(t)::text as snapshot from ' + table + ' t order by ' + ordering)
            .getValues('snapshot', String)
    }
}
