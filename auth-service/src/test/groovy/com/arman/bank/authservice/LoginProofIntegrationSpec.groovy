package com.arman.bank.authservice

import com.arman.bank.authservice.application.LoginProofStore
import com.arman.bank.authservice.domain.LoginProof
import com.arman.bank.authservice.domain.LoginProof.*
import com.arman.bank.authservice.infrastructure.PostgresLoginProofStore
import com.arman.bank.authservice.infrastructure.LoginProofConfiguration
import com.arman.bank.runtime.Database
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification

import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.*
import java.util.function.Function
import io.javalin.Javalin
import groovy.json.JsonOutput
import java.net.http.*

/** Real PostgreSQL storage acceptance, not production ACTIVE/projection or provider acceptance.
 * Positive authority exists ONLY in qa_synthetic_proof_authority, read through the owning
 * transaction's DSLContext. No verification receipt, ACTIVE enrollment, bank session,
 * notification outbox or financial result is fabricated by this fixture.
 */
class LoginProofIntegrationSpec extends Specification {
    static final String ISSUER = 'https://issuer.example/realm'
    static final String WRITER = 'synthetic-provider'
    static final String CLIENT = 'armoney-web'
    PostgreSQLContainer postgres
    Database database
    PostgresLoginProofStore store
    UUID identity = UUID.randomUUID()
    Binding binding = new Binding(ISSUER, 'synthetic-subject', CLIENT, 'synthetic-user-session')
    byte[] retentionKey = new byte[32]
    Map preserved
    List history

    def setup() {
        postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations('classpath:db/migration').target('6').load().migrate()
        def connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        try {
            def sql = DSL.using(connection, SQLDialect.POSTGRES)
            sql.execute('''insert into identities(id,email,password_hash,registration_phone,notification_email,notification_email_verified)
                values (?,?,?,?,?,true)''', identity, 'legacy@example.test', 'synthetic-legacy-password-digest',
                '+971501234567', 'verified@example.test')
            sql.execute('insert into external_identities(issuer,subject,identity_id) values (?,?,?)', ISSUER, binding.subject(), identity)
            sql.execute("insert into sessions(token_hash,identity_id,expires_at) values (?,?,clock_timestamp()+interval '1 hour')", 'a' * 64, identity)
            sql.execute('''insert into verification_enrollments(id,issuer,subject,binding_digest,binding_key_id,phone,email,state,created_at,expires_at)
                values (?,?,?,?,?,?,?,'PENDING_CONTACTS',clock_timestamp(),clock_timestamp()+interval '1 hour')''',
                UUID.randomUUID(), ISSUER, 'pending-subject', new byte[32], 'test', '+971521234567', 'pending@example.test')
            UUID enrollment = sql.fetchOne('select id from verification_enrollments').get('id', UUID)
            UUID challenge = UUID.randomUUID()
            sql.execute('''insert into verification_challenges(id,enrollment_id,purpose,contact_version,state,digest_key_id,created_at,expires_at,resend_at)
                values (?,?,'REGISTRATION_EMAIL',1,'CONSUMED','test',statement_timestamp(),statement_timestamp()+interval '5 minutes',statement_timestamp())''', challenge, enrollment)
            sql.execute('''insert into verification_receipts(id,enrollment_id,challenge_id,purpose,contact_version,created_at)
                values (?,?,?,'REGISTRATION_EMAIL',1,statement_timestamp())''', UUID.randomUUID(), enrollment, challenge)
            sql.execute('''insert into verification_delivery_outbox(id,challenge_id,enrollment_id,purpose,contact_version,state,key_id,available_at,expires_at)
                values (?,?,?,'REGISTRATION_EMAIL',1,'ACCEPTED','test',statement_timestamp(),statement_timestamp()+interval '5 minutes')''', UUID.randomUUID(), challenge, enrollment)
            sql.execute('''insert into verification_commands(requester_digest,command_id,hash_key_id,payload_hash,result,created_at,expires_at)
                values (?,?,'test',?,'synthetic-historical-result',statement_timestamp(),statement_timestamp()+interval '1 hour')''', new byte[32], UUID.randomUUID(), new byte[32])
            preserved = snapshots(sql)
            history = sql.fetch('select version,script,checksum from flyway_schema_history order by installed_rank').intoMaps()
        } finally { connection.close() }
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        Arrays.fill(retentionKey, (byte) 31)
        database.transaction { sql ->
            sql.execute('''create table qa_synthetic_proof_authority
                (identity_id uuid primary key, permitted boolean not null, epoch bigint not null, email text not null)''')
            sql.execute('insert into qa_synthetic_proof_authority values (?,true,1,?)', identity, 'verified@example.test')
        }
        store = syntheticStore()
    }

    def cleanup() { database?.close(); postgres?.stop() }

    def 'populated published V6 migrates through V7 and V8 to V9 without modifying old rows or checksums'() {
        expect:
        database.transaction { sql ->
            assert snapshots(sql) == preserved
            assert sql.fetch('select version,script,checksum from flyway_schema_history where cast(version as integer) <= ? order by installed_rank', 6).intoMaps() == history
            assert sql.fetchOne('select version from flyway_schema_history order by installed_rank desc limit 1').get('version') == '11'
            ['verification_authorities','verification_candidates','verification_authority_phone_claims','verification_flow_barriers','verification_flows','verification_authority_admission','verification_authority_commands'].each { table ->
                assert sql.fetchOne('select count(*) as n from ' + table).get('n', Long) == 0
            }
            assert sql.fetch('select * from verification_authority_config').intoMaps() == [[id: 1, retention_key_fingerprint: null]]
            ['verification_challenges','verification_receipts'].each { table ->
                assert sql.fetchOne('select count(*) as n from ' + table + ' where candidate_id is not null').get('n', Long) == 0
            }
            assert sql.fetch('select version from flyway_schema_history order by installed_rank').getValues('version', String) == ['1','2','3','4','5','6','7','8','9','10','11']
            ['sso_login_proofs','sso_login_events','sso_login_proof_commands','sso_login_proof_admission',
             'auth_verification_projection_commands','auth_verification_projection_tombstones','auth_verification_projection_admission'].each { table ->
                assert sql.fetchOne('select count(*) as n from ' + table).get('n', Long) == 0
            }
            assert sql.fetchOne('select count(*) as n from sso_login_proof_control where id=1').get('n', Long) == 1
            assert sql.fetchOne('select count(*) as n from auth_verification_projection_control where id=1').get('n', Long) == 1
            true
        }
    }

    def 'missing transaction eligibility adapter fails closed without proof event or command'() {
        given:
        def unavailable = new PostgresLoginProofStore(database, policy(), retentionKey,
            ({ DSLContext sql -> null } as Function))
        when:
        write(unavailable, command())
        then:
        def rejected = thrown(Failure)
        rejected.error() == LoginProof.Error.UNAVAILABLE
        counts() == [0L, 0L, 0L]
    }

    def 'actual production configuration denies otherwise valid writes with unavailable authority'() {
        given:
        def config = new LoginProofConfiguration([
            AUTH_LOGIN_PROOF_BRIDGE_ENABLED: 'true', SSO_LOGIN_PROOF_WRITER_ID: WRITER,
            SSO_LOGIN_PROOF_WRITE_KEY: 'w' * 40, SSO_LOGIN_PROOF_RESOLVE_KEY: 'r' * 40,
            SSO_LOGIN_PROOF_RETENTION_KEY: Base64.encoder.encodeToString(retentionKey),
            SSO_ISSUER: ISSUER, SSO_LOGIN_PROOF_CLIENT_IDS: CLIENT, SSO_LOGIN_PROOF_CODE_KEY_IDS: 'current'])
        def routes = config.routes(database)
        def server = Javalin.create { routes.configure(it) }.start('127.0.0.1', 0)
        def input = command()
        def body = JsonOutput.toJson([command_id: input.commandId().toString(), code_digest: input.codeDigest(),
            code_key_id: input.codeKeyId(), issuer: input.issuer(), subject: input.subject(), client_id: input.clientId(),
            user_session_id: input.userSessionId(), authentication_session_id: input.authenticationSessionId(),
            issued_at: input.issuedAt().toString(), expires_at: input.expiresAt().toString(), kind: 'interactive',
            event_id: input.eventId().toString(), password_authenticated_at: input.passwordAuthenticatedAt().toString()])
        when:
        def response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create('http://127.0.0.1:' + server.port() + '/internal/sso-login-proofs'))
            .timeout(java.time.Duration.ofSeconds(5)).header('Content-Type', 'application/json')
            .header('X-ArMoney-Login-Proof-Write-Key', 'w' * 40)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        then:
        response.statusCode() == 503
        response.headers().firstValue('Cache-Control').orElse('') == 'no-store'
        response.body() == '{"error":"login_proof_request_rejected"}'
        counts() == [0L, 0L, 0L]
        cleanup:
        server?.stop()
    }

    def 'synthetic transaction-bound interactive snapshot survives lost response and new database pool'() {
        given:
        def input = command()
        def committed = write(store, input) // Caller deliberately discards its response.
        database.close()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        store = syntheticStore()
        when:
        def replay = write(store, input)
        then:
        replay == committed
        resolve(store, lookup(input)) == committed
        counts() == [1L, 1L, 1L]
        database.transaction { snapshots(it) } == preserved
    }

    def 'concurrent exact commands across two stores commit exactly one immutable snapshot'() {
        given:
        def input = command()
        def second = syntheticStore()
        when:
        def results = parallel((0..<8).collect { index -> { -> (index % 2 ? second : store).write(WRITER, input) } })
        then:
        results.every { it instanceof Snapshot }
        results.toSet().size() == 1
        counts() == [1L, 1L, 1L]
    }

    def 'same command with changed payload conflicts and preserves original across concurrent writers'() {
        given:
        def original = command()
        def changed = copy(original, UUID.randomUUID().toString().replace('-', '') * 2)
        when:
        def results = parallel([{ -> attempt { write(store, original) } },
                                { -> attempt { syntheticStore().write(WRITER, changed) } }])
        then:
        results.count { it instanceof Snapshot } == 1
        results.count { it == LoginProof.Error.CONFLICT } == 1
        counts() == [1L, 1L, 1L]
    }

    def 'resolve rejects each changed immutable subject client session key or issuer binding'() {
        given:
        def input = command()
        write(store, input)
        expect:
        [new Resolve(input.codeDigest(), 'previous', ISSUER, binding.subject(), CLIENT, binding.userSessionId()),
         new Resolve(input.codeDigest(), 'current', ISSUER, 'another-subject', CLIENT, binding.userSessionId()),
         new Resolve(input.codeDigest(), 'current', ISSUER, binding.subject(), 'armoney-ios', binding.userSessionId()),
         new Resolve(input.codeDigest(), 'current', ISSUER, binding.subject(), CLIENT, 'another-session')].every { value ->
            attempt { resolve(store, value) } == LoginProof.Error.CONFLICT
        }
        attempt { store.resolve('unconfigured-writer', lookup(input)) } == LoginProof.Error.UNAUTHORIZED
        counts() == [1L, 1L, 1L]
    }

    def 'write replay rechecks current synthetic eligibility and recipient binding'() {
        given:
        def input = command()
        write(store, input)
        when:
        execute('update qa_synthetic_proof_authority set permitted=false')
        then:
        attempt { write(store, input) } == LoginProof.Error.CONFLICT
        when:
        execute('update qa_synthetic_proof_authority set permitted=true,epoch=2,email=?', 'replacement@example.test')
        then:
        attempt { write(store, input) } == LoginProof.Error.CONFLICT
        counts() == [1L, 1L, 1L]
    }

    def 'database expiry rejects write replay and resolve despite valid original client timestamps'() {
        given:
        def input = command()
        write(store, input)
        execute("update sso_login_proofs set issued_at=clock_timestamp()-interval '3 seconds',expires_at=clock_timestamp()-interval '1 second'")
        expect:
        attempt { resolve(store, lookup(input)) } == LoginProof.Error.EXPIRED
        attempt { write(store, input) } == LoginProof.Error.EXPIRED
        counts() == [1L, 1L, 1L]
    }

    def 'capacity is global across store instances while exact replay remains readable'() {
        given:
        def limited = syntheticStore(new Policy([ISSUER] as Set, [CLIENT, 'armoney-ios'] as Set,
            ['current', 'previous'] as Set, [WRITER] as Set, 1, 1, 1))
        def input = command()
        def recorded = write(limited, input)
        def second = syntheticStore(new Policy([ISSUER] as Set, [CLIENT, 'armoney-ios'] as Set,
            ['current', 'previous'] as Set, [WRITER] as Set, 1, 1, 1))
        expect:
        write(second, input) == recorded
        attempt { write(second, command()) } == LoginProof.Error.RATE_LIMITED
        counts() == [1L, 1L, 1L]
    }

    def 'durable writer admission is shared across instances and survives restart'() {
        given:
        def second = syntheticStore()
        // Fixed DB refill time in the future prevents wall-clock scheduler speed changing the burst assertion.
        store.admit(WRITER)
        execute("update sso_login_proof_admission set credits=19,refilled_at=clock_timestamp()+interval '1 hour'")
        // Fixture clock fence makes this an exact burst test, independent of a slow CI scheduler.
        execute('''create function qa_freeze_refill() returns trigger language plpgsql as $$
            begin NEW.refilled_at := clock_timestamp()+interval '1 hour'; return NEW; end $$''')
        execute('''create trigger qa_freeze_refill before update on sso_login_proof_admission
            for each row execute function qa_freeze_refill()''')
        when:
        def results = parallel((0..<24).collect { index -> { -> attempt { (index % 2 ? second : store).admit(WRITER); 'ADMITTED' } } })
        then:
        results.count('ADMITTED') == 19
        results.count(LoginProof.Error.RATE_LIMITED) == 5
        when:
        database.close()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        store = syntheticStore()
        execute("update sso_login_proof_admission set credits=0,refilled_at=clock_timestamp()+interval '1 hour'")
        then:
        attempt { store.admit(WRITER) } == LoginProof.Error.RATE_LIMITED
        when:
        execute('drop trigger qa_freeze_refill on sso_login_proof_admission')
        execute("update sso_login_proof_admission set refilled_at=clock_timestamp()-interval '1 second'")
        then:
        attempt { store.admit(WRITER); 'ADMITTED' } == 'ADMITTED'
    }

    def 'logical recipient expiry blocks replay before cleanup and erasure preserves keyed tombstone'() {
        given:
        def input = command()
        write(store, input)
        def before = database.transaction { it.fetchOne('select recipient_digest,identity_digest,payload_hash from sso_login_events join sso_login_proofs using(issuer,event_id)').intoMap() }
        execute("""update sso_login_events set created_at=statement_timestamp()-interval '25 hours',
            recipient_expires_at=statement_timestamp()-interval '25 hours'+interval '24 hours',
            retained_until=statement_timestamp()-interval '25 hours'+interval '7 days'""")
        expect:
        attempt { write(store, input) } == LoginProof.Error.CONFLICT
        when:
        store.cleanup()
        then:
        database.transaction { sql ->
            def row = sql.fetchOne('select * from sso_login_events')
            assert row.get('verified_email') == null
            assert row.get('identity_id') == null
            assert Arrays.equals(row.get('recipient_digest', byte[]), before.recipient_digest as byte[])
            assert Arrays.equals(row.get('identity_digest', byte[]), before.identity_digest as byte[])
            true
        }
        counts() == [1L, 1L, 1L]
        attempt { write(store, input) } == LoginProof.Error.CONFLICT
    }

    def 'synthetic reuse snapshot has no event or notification eligibility'() {
        given:
        def source = command()
        def reuse = new Write(source.commandId(), source.codeDigest(), source.codeKeyId(), source.issuer(),
            source.subject(), source.clientId(), source.userSessionId(), source.authenticationSessionId(),
            source.issuedAt(), source.expiresAt(), Kind.REUSE, null, null)
        when:
        def result = write(store, reuse)
        then:
        result.kind() == Kind.REUSE
        result.eventId() == null
        !result.loginNoticeEligible()
        resolve(store, lookup(reuse)) == result
        counts() == [1L, 0L, 1L]
    }

    def 'expired seven day retention removes commands proofs and events in foreign key order'() {
        given:
        write(store, command())
        ['sso_login_proofs', 'sso_login_proof_commands'].each { table ->
            execute('update ' + table + " set created_at=statement_timestamp()-interval '8 days',retained_until=statement_timestamp()-interval '1 day'")
        }
        execute("""update sso_login_events set created_at=statement_timestamp()-interval '8 days',
            recipient_expires_at=statement_timestamp()-interval '7 days',retained_until=statement_timestamp()-interval '1 day'""")
        when:
        store.cleanup()
        then:
        counts() == [0L, 0L, 0L]
        database.transaction { snapshots(it) } == preserved
    }

    private Snapshot write(PostgresLoginProofStore target, Write input) {
        target.admit(WRITER)
        target.write(WRITER, input)
    }
    private Snapshot resolve(PostgresLoginProofStore target, Resolve input) {
        target.admit(WRITER)
        target.resolve(WRITER, input)
    }
    private Policy policy() { Policy.defaults([ISSUER] as Set, [CLIENT, 'armoney-ios'] as Set,
        ['current', 'previous'] as Set, [WRITER] as Set) }

    private PostgresLoginProofStore syntheticStore(Policy selected = policy()) {
        new PostgresLoginProofStore(database, selected, retentionKey, ({ DSLContext sql ->
            ({ Binding requested, Instant now ->
                def row = sql.fetchOne('select * from qa_synthetic_proof_authority where identity_id=? for update', identity)
                new Activation(requested, identity, row.get('permitted', Boolean) ? 'ACTIVE' : 'SUSPENDED',
                    true, true, 1, 1, row.get('epoch', Long), UUID.fromString('00000000-0000-0000-0000-000000000001'),
                    2, 1, row.get('epoch', Long), 'b' * 64, true, row.get('email', String), true, true, now)
            } as LoginProofStore.EligibilityPort)
        } as Function))
    }

    private Write command() {
        Instant now = database.transaction { it.fetchOne('select clock_timestamp() as now').get('now', OffsetDateTime).toInstant() }.truncatedTo(ChronoUnit.SECONDS)
        new Write(UUID.randomUUID(), UUID.randomUUID().toString().replace('-', '') * 2, 'current', ISSUER,
            binding.subject(), CLIENT, binding.userSessionId(), 'synthetic-auth-session', now,
            now.plusSeconds(120), Kind.INTERACTIVE, UUID.randomUUID(), now.minusSeconds(10))
    }
    private static Write copy(Write input, String digest) {
        new Write(input.commandId(), digest, input.codeKeyId(), input.issuer(), input.subject(), input.clientId(),
            input.userSessionId(), input.authenticationSessionId(), input.issuedAt(), input.expiresAt(), input.kind(), input.eventId(), input.passwordAuthenticatedAt())
    }
    private static Resolve lookup(Write input) { new Resolve(input.codeDigest(), input.codeKeyId(), input.issuer(), input.subject(), input.clientId(), input.userSessionId()) }
    private List<Long> counts() { database.transaction { sql -> ['sso_login_proofs','sso_login_events','sso_login_proof_commands'].collect { table -> sql.fetchOne('select count(*) as n from ' + table).get('n', Long) } } }
    private void execute(String query, Object... values) { database.transaction { it.execute(query, values) } }
    private static Object attempt(Closure action) { try { action.call() } catch (Failure failure) { failure.error() } }
    private static List parallel(List<Closure> actions) {
        def executor = Executors.newFixedThreadPool(actions.size())
        try {
            def start = new CountDownLatch(1)
            def futures = actions.collect { action -> executor.submit({ start.await(); action.call() } as Callable) }
            start.countDown()
            futures.collect { it.get(20, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow(); assert executor.awaitTermination(5, TimeUnit.SECONDS) }
    }
    private static Map snapshots(DSLContext sql) {
        // Fixed identifiers only, never client-controlled SQL.
        [identities: 'id', sessions: 'token_hash', external_identities: 'issuer,subject',
         verification_enrollments: 'id', verification_challenges: 'id', verification_receipts: 'id',
         verification_delivery_outbox: 'id', verification_commands: 'requester_digest,command_id'].collectEntries { table, ordering ->
            // Only new V9 candidate_id is excluded; every published column remains in the snapshot.
            [(table): sql.fetch('select ' + (table in ['verification_challenges','verification_receipts'] ? "(to_jsonb(t) - 'candidate_id')::text" : 'row_to_json(t)::text') + ' as snapshot from ' + table + ' t order by ' + ordering).getValues('snapshot', String)]
        }
    }
}
