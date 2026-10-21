package com.arman.bank.authservice

import com.arman.bank.authservice.application.VerificationProjectionStore.AuthorityPort
import com.arman.bank.authservice.domain.IdentityVerification
import com.arman.bank.authservice.domain.Verification
import com.arman.bank.authservice.domain.VerificationProjection
import com.arman.bank.authservice.infrastructure.*
import com.arman.bank.runtime.Database
import org.jooq.DSLContext
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.sql.DriverManager
import java.time.*
import java.util.concurrent.*
import java.util.function.Function

/** Actual PostgreSQL blocking/rollback acceptance. Each entry must wait on the
 * verification singleton BEFORE owning projection control; a future completing
 * or taking the second lock first is a failure, even if its final response denies.
 * No positive authority or remote provider call is involved.
 */
class VerificationLockOrderIntegrationSpec extends Specification {
    static final String WRITER = 'synthetic_provider'
    static final String ISSUER = 'https://issuer.example/realm'
    PostgreSQLContainer postgres
    Database database
    PostgresVerificationStore legacy
    PostgresVerificationProjectionStore projections
    PostgresIdentityVerificationStore authority
    IdentityVerification.Flow flow

    def setup() {
        postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        database = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        def crypto = new VerificationCrypto('test', [test:([11] * 32) as byte[]], [test:([22] * 32) as byte[]])
        legacy = new PostgresVerificationStore(database, crypto)
        projections = new PostgresVerificationProjectionStore(database, VerificationProjection.Policy.defaults([WRITER] as Set),
            ([41] * 32) as byte[], ({ DSLContext sql -> ({ UUID identity, Instant now ->
                throw new VerificationProjection.Failure(VerificationProjection.Error.UNAVAILABLE)
            } as AuthorityPort) } as Function))
        authority = new PostgresIdentityVerificationStore(database, crypto, ([41] * 32) as byte[],
            IdentityVerification.Policy.defaults([ISSUER] as Set, ['armoney-web'] as Set, [WRITER] as Set), projections)
        flow = new IdentityVerification.Flow(ISSUER, 'synthetic-subject', 'armoney-web', 'synthetic-root', 'synthetic-tab',
            Base64.urlEncoder.withoutPadding().encodeToString(([3] * 32) as byte[]))
    }
    def cleanup() { database?.close(); postgres?.stop() }

    def 'every projection core receipt and authority entry acquires the same control prefix first'() {
        given:
        def blocker = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        blocker.autoCommit = false
        def held = blocker.createStatement()
        def pidRows = held.executeQuery('select pg_backend_pid()')
        assert pidRows.next()
        int blockingPid = pidRows.getInt(1)
        pidRows.close()
        def locked = held.executeQuery('select id from verification_control where id=1 for update')
        assert locked.next()
        def executor = Executors.newSingleThreadExecutor()
        when:
        def future = executor.submit({ invoke(operation) } as Callable)
        awaitBlockedPrefix(blockingPid)
        then:
        !future.done
        when: 'a second independent connection can still acquire projection control'
        def probe = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        probe.autoCommit = false
        def probeStatement = probe.createStatement()
        def available = probeStatement.executeQuery('select id from auth_verification_projection_control where id=1 for update nowait')
        then:
        available.next()
        when:
        available.close(); probeStatement.close(); probe.rollback()
        locked.close(); blocker.rollback()
        def result = future.get(10, TimeUnit.SECONDS)
        then:
        result != null
        database.ready()
        cleanup:
        probe?.rollback(); probe?.close()
        locked?.close(); held?.close(); blocker?.rollback(); blocker?.close()
        executor?.shutdownNow(); executor?.awaitTermination(5, TimeUnit.SECONDS)
        where:
        operation << ['projection_allocate','projection_status','projection_claim','projection_finish','projection_cleanup',
            'legacy_enroll','legacy_status','legacy_issue','legacy_consume','legacy_claim','legacy_finish','legacy_expire',
            'authority_admit','authority_status','authority_cleanup','authority_enroll','authority_issue','authority_consume','authority_prepare','authority_attach','proof_admit','proof_cleanup','auth_gate','auth_validate','auth_revoke']
    }

    def 'concurrent claim finish admission status cleanup and receipt writers complete without deadlock or banking side effects'() {
        given:
        def operations = ['projection_claim','projection_finish','projection_status','projection_cleanup',
            'legacy_status','legacy_issue','legacy_consume','legacy_claim','legacy_finish','legacy_expire',
            'authority_admit','authority_cleanup','authority_enroll','authority_issue','authority_consume','authority_prepare','authority_attach','proof_admit','proof_cleanup','auth_gate','auth_validate','auth_revoke']
        def executor = Executors.newFixedThreadPool(operations.size())
        def gate = new CountDownLatch(1)
        when:
        def futures = operations.collect { name -> executor.submit({ gate.await(); invoke(name) } as Callable) }
        gate.countDown()
        def results = futures.collect { it.get(20, TimeUnit.SECONDS) }
        then:
        results.size() == operations.size()
        database.transaction { sql ->
            assert sql.fetchOne('select count(*) as n from identities').get('n', Long) == 0
            assert sql.fetchOne('select count(*) as n from sessions').get('n', Long) == 0
            assert sql.fetchOne('select count(*) as n from sso_login_events').get('n', Long) == 0
            true
        }
        cleanup:
        executor?.shutdownNow(); executor?.awaitTermination(5, TimeUnit.SECONDS)
    }

    def 'caller-owned allocation rejects absent control marker and failed transaction releases both singleton locks'() {
        given:
        def input = command()
        when:
        database.transaction { sql -> projections.allocateInTransaction(sql, WRITER, input) }
        then:
        def unowned = thrown(VerificationProjection.Failure)
        unowned.error() == VerificationProjection.Error.UNAVAILABLE
        when: 'both controls are legitimately locked but the production authority is unavailable'
        database.transaction { sql ->
            PostgresVerificationProjectionStore.lockControls(sql)
            projections.allocateInTransaction(sql, WRITER, input)
        }
        then:
        def denied = thrown(VerificationProjection.Failure)
        denied.error() == VerificationProjection.Error.UNAVAILABLE
        when:
        def connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        connection.autoCommit = false
        def statement = connection.createStatement()
        def first = statement.executeQuery('select id from verification_control where id=1 for update nowait')
        then:
        first.next()
        when:
        first.close()
        def second = statement.executeQuery('select id from auth_verification_projection_control where id=1 for update nowait')
        then:
        second.next()
        database.transaction { it.fetchOne('select count(*) as n from auth_verification_projection_commands').get('n', Long) } == 0
        cleanup:
        second?.close(); first?.close(); statement?.close(); connection?.rollback(); connection?.close()
    }

    private Object invoke(String operation) {
        def input = command()
        def binding = new Verification.Binding(ISSUER, 'synthetic-subject', 'synthetic-legacy-session')
        // These negative lock-prefix probes call repository entries directly, without
        // an admission call masking an entry's own lock order. No positive flow exists.
        Instant startedAt = database.transaction { it.fetchOne('select clock_timestamp() as now').get('now',OffsetDateTime).toInstant() }
        def evidence = observation(startedAt)
        try {
            def proof=new PostgresLoginProofStore(database,com.arman.bank.authservice.domain.LoginProof.Policy.defaults([ISSUER] as Set,['armoney-web'] as Set,['test'] as Set,[WRITER] as Set),([42]*32) as byte[],{sql -> {bindingValue,time -> throw new com.arman.bank.authservice.domain.LoginProof.Failure(com.arman.bank.authservice.domain.LoginProof.Error.UNAVAILABLE)} as com.arman.bank.authservice.application.LoginProofStore.EligibilityPort})
            switch (operation) {
                case 'proof_admit': proof.admit(WRITER); break
                case 'proof_cleanup': proof.cleanup(); break
                case 'auth_gate': new PostgresAuthStore(database,false).requireBankingAccess(UUID.randomUUID()); break
                case 'auth_validate': new PostgresAuthStore(database,false).findSession('a'*64,Instant.now()); break
                case 'auth_revoke': new PostgresAuthStore(database,false).revoke('a'*64); break
                case 'projection_allocate': projections.allocate(WRITER, input); break
                case 'projection_status': projections.status(WRITER, UUID.randomUUID()); break
                case 'projection_claim': projections.claim(WRITER); break
                case 'projection_finish': projections.finish(claim(input), VerificationProjection.Result.retry()); break
                case 'projection_cleanup': projections.cleanup(); break
                case 'legacy_enroll': legacy.enroll(new Verification.EnrollmentCommand(UUID.randomUUID(), binding, '+971501234567','synthetic@example.test')); break
                case 'legacy_status': legacy.status(UUID.randomUUID(), binding); break
                case 'legacy_issue': legacy.issue(new Verification.ChallengeCommand(UUID.randomUUID(), UUID.randomUUID(), binding, Verification.Purpose.REGISTRATION_EMAIL,1)); break
                case 'legacy_consume': legacy.consume(new Verification.ConsumeCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), binding, Verification.Purpose.REGISTRATION_EMAIL,1,'123456')); break
                case 'legacy_claim': legacy.claimDelivery(); break
                case 'legacy_finish': legacy.finishDelivery(UUID.randomUUID(),UUID.randomUUID(),true); break
                case 'legacy_expire': legacy.expireDeliveryPayloads(); break
                case 'authority_admit': authority.admit(WRITER,flow); break
                case 'authority_status':
                    authority.status(WRITER,UUID.randomUUID(),flow,evidence,startedAt); break
                case 'authority_cleanup': authority.cleanup(); break
                case 'authority_enroll': authority.enroll(WRITER,new IdentityVerification.Enrollment(UUID.randomUUID(),flow,'+971501234567','synthetic@example.test'),evidence,startedAt); break
                case 'authority_issue': authority.issue(WRITER,new IdentityVerification.Issue(UUID.randomUUID(),UUID.randomUUID(),flow,Verification.Purpose.REGISTRATION_EMAIL,1),evidence,startedAt); break
                case 'authority_consume': authority.consume(WRITER,new IdentityVerification.Consume(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),flow,Verification.Purpose.REGISTRATION_EMAIL,1,'123456'),evidence,startedAt); break
                case 'authority_prepare': authority.prepare(WRITER,UUID.randomUUID(),UUID.randomUUID(),flow,evidence,startedAt); break
                case 'authority_attach': authority.attach(WRITER,claim(input),VerificationProjection.Result.retry()); break
                default: throw new AssertionError('Unrecognized finite test operation')
            }
            'COMPLETED'
        } catch (VerificationProjection.Failure denied) { denied.error().name() }
        catch (Verification.Failure denied) { denied.error().name() }
        catch (com.arman.bank.authservice.application.AuthFailure denied) {
            if (operation != 'auth_gate' || denied.kind() != com.arman.bank.authservice.application.AuthFailure.Kind.UNAUTHORIZED) throw denied
            'UNAUTHORIZED'
        }
        // SQL/deadlock/timeouts deliberately propagate and fail the regression.
    }
    private static VerificationProjection.Command command() { VerificationProjection.create(UUID.randomUUID(),UUID.randomUUID(),0,1,'+971501234567',1,1) }
    private IdentityVerification.Observation observation(Instant startedAt) {
        new IdentityVerification.Observation(flow,'+971501234567','synthetic@example.test','+971501234567',true,true,startedAt.plusSeconds(60),startedAt)
    }
    private static VerificationProjection.Claim claim(VerificationProjection.Command input) {
        def now = Instant.now()
        new VerificationProjection.Claim(WRITER,input,HttpVerificationProjection.body(input),UUID.randomUUID(),now.plusSeconds(30),now.plusSeconds(60),1)
    }
    private void awaitBlockedPrefix(int blocker) {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime()<deadline) {
            boolean waiting = database.transaction { sql -> sql.fetchOne('''select count(*) as n from pg_stat_activity
                where ? = any(pg_blocking_pids(pid)) and wait_event_type='Lock' and query like '%verification_control%' ''', blocker).get('n',Long)>0 }
            if(waiting) return
            Thread.sleep(20)
        }
        throw new AssertionError('Entry did not block on verification control before projection control')
    }
}
