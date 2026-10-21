package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.LegacyMigrationOtpService
import com.arman.bank.authservice.domain.LegacyMigrationOtp as O
import com.arman.bank.authservice.domain.LegacyMigrationFinalization as F
import com.sun.net.httpserver.HttpServer
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.time.*

/** Real owned PostgreSQL + genuine creator capabilities, RSA validation, exact12 HTTP and actual User stage.
 * Token/JWKS and OTP delivery are explicitly synthetic. This proves no Keycloak/SMTP/native/banking acceptance.
 * Requires root's existing isolated User fixture gate/installDist. No positive receipt/ACK/journal SQL seed. */
class LegacyMigrationFinalizationIntegrationSpec extends Specification {
    LegacyMigrationManagedProofIntegrationSpec fixture
    PostgreSQLContainer userPg
    LegacyMigrationUserStageFixture user
    HttpServer unavailableAuth
    LegacyMigrationOtpCrypto otpCrypto
    PostgresLegacyMigrationOtpStore otp
    LegacyMigrationOtpService service
    PostgresLegacyMigrationFinalizationStore journal
    def setup(){
        fixture=new LegacyMigrationManagedProofIntegrationSpec()
        // Direct reuse does not run Spock's generated field initializer. Restore the
        // fixture's exact closed synthetic state before its genuine resource setup.
        fixture.callbackNonces=new java.util.concurrent.ConcurrentHashMap<String,String>()
        fixture.callbackSubjects=new java.util.concurrent.ConcurrentHashMap<String,String>()
        fixture.contacts=new java.util.concurrent.ConcurrentHashMap<String,Map>()
        fixture.observedSubjects=new java.util.concurrent.CopyOnWriteArrayList<String>()
        fixture.diagnosticPhases=new java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger>()
        fixture.signedSubject='signed-existing'
        fixture.tokenLifetime=60L
        fixture.negative=false
        fixture.tokenCalls=0
        fixture.observerCalls=0
        fixture.setup()
        userPg=new PostgreSQLContainer('postgres:17.6-alpine');userPg.start()
        unavailableAuth=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        unavailableAuth.createContext('/'){exchange->exchange.sendResponseHeaders(503,-1);exchange.close()};unavailableAuth.start()
        def repository=java.nio.file.Path.of('').toAbsolutePath();if(!java.nio.file.Files.isDirectory(repository.resolve('user-service')))repository=repository.parent
        user=LegacyMigrationUserStageFixture.launch(repository,userPg,URI.create('http://127.0.0.1:'+unavailableAuth.address.port))
        otpCrypto=new LegacyMigrationOtpCrypto('otp',[otp:([83]*32) as byte[]],[otp:([84]*32) as byte[]])
        otp=new PostgresLegacyMigrationOtpStore(fixture.database,fixture.custody,otpCrypto,fixture.ACTOR,true,fixture.managed)
        service=new LegacyMigrationOtpService(otp,user.stagePort())
        journal=new PostgresLegacyMigrationFinalizationStore(fixture.database,otp,fixture.managed,fixture.crypto,fixture.ACTOR,true)
    }
    def cleanup(){user?.close();unavailableAuth?.stop(0);userPg?.stop();fixture?.cleanup()}
    def admitted(boolean sso){
        def original
        if(sso){
            original=fixture.historicalSsoOwner();user.bootstrap(original.identityId,fixture.PHONE)
            def redirect=fixture.account.start(UUID.randomUUID());fixture.interactive(redirect,'legacy_migration_account_attempts');original.admission=fixture.ssoAdmissionWithCandidate(redirect)
        }else{original=fixture.localOwner();user.bootstrap(original.identityId,fixture.PHONE)}
        def managed=fixture.managed.start(original.admission.migrationId(),original.admission.ownerCapability(),UUID.randomUUID())
        fixture.interactive(managed,'legacy_migration_managed_attempts');fixture.managed.complete(fixture.callback(managed))
        service.stage(original.admission.migrationId(),original.admission.ownerCapability());original
    }
    def consumed(def original,O.Purpose purpose){
        def migration=original.admission.migrationId();def capability=original.admission.ownerCapability()
        def challenge=service.issue(migration,capability,UUID.randomUUID(),purpose)
        def lease=otp.claimDelivery().orElseThrow()
        assert lease.envelope().challengeId()==challenge.challengeId()
        def payload=otpCrypto.decrypt(lease.envelope(),lease.encrypted())
        // Controlled synthetic delivery ACK only; never claimed real email/SMS delivery.
        assert otp.finishDelivery(lease.deliveryId(),lease.fence(),true)
        def receipt=service.consume(migration,capability,UUID.randomUUID(),challenge.challengeId(),purpose,payload.code())
        assert receipt.state()=='CONSUMED';receipt
    }
    def query(UUID migration){fixture.database.transaction{sql->def row=sql.fetchOne('select stage_operation_id,decision_command_id,payload_hash from legacy_migration_otp_stages s join legacy_migration_otp_stage_acks a using(migration_id) where migration_id=?',migration);new F.DecisionQuery(migration,row.get('stage_operation_id',UUID),row.get('decision_command_id',UUID),row.get('payload_hash',String))}}
    def 'actual dual consumed producer receipts authorize immutable publication without final authority for both account modes'(){
        given:
        def original=admitted(sso)
        def email=consumed(original,O.Purpose.EMAIL);def phone=consumed(original,O.Purpose.PHONE)
        def q=query(original.admission.migrationId())
        def before=fixture.database.transaction{sql->sql.fetch('select token_hash,identity_id,expires_at from sessions order by token_hash').intoMaps()}
        when:
        def decision=journal.inRequest(q.migrationId(),original.admission.ownerCapability()){request->request.authorize()}
        def proof=journal.evidence(new F.EvidenceQuery(q.migrationId(),UUID.fromString(decision.fields().decision_id),q.stageOperationId(),q.commandId(),q.payloadHash()))
        then:
        proof.fields().email_receipt_id==email.receiptId().toString()
        proof.fields().phone_receipt_id==phone.receiptId().toString()
        proof.fields().receipt_snapshot_hash==F.receiptHash(proof.fields())
        decision==journal.decision(q)
        fixture.count('legacy_migration_publication_decisions')==1
        fixture.count('managed_registration_principals')==0
        fixture.database.transaction{sql->sql.fetch('select token_hash,identity_id,expires_at from sessions order by token_hash').intoMaps()}==before
        fixture.auth.me('Bearer '+original.token).id()==original.identityId
        when:
        def replay=journal.inRequest(q.migrationId(),original.admission.ownerCapability()){request->request.authorize()}
        then:replay==decision;fixture.count('legacy_migration_publication_decisions')==1
        when:journal.decision(new F.DecisionQuery(q.migrationId(),q.stageOperationId(),UUID.randomUUID(),q.payloadHash()))
        then:thrown(F.Failure)
        when:fixture.database.transaction{sql->sql.execute("update legacy_migration_publication_decisions set state='ABORTED' where migration_id=?",q.migrationId())}
        then:thrown(RuntimeException)
        when:fixture.database.transaction{sql->sql.execute('delete from legacy_migration_publication_decisions where migration_id=?',q.migrationId())}
        then:thrown(RuntimeException)
        where:sso << [false,true]
    }
    def 'one consumed purpose cannot authorize and escaped request cannot retry'(){
        given:
        def original=admitted(false);consumed(original,O.Purpose.EMAIL)
        def migration=original.admission.migrationId();def capability=original.admission.ownerCapability()
        when:journal.inRequest(migration,capability){request->request.authorize()}
        then:thrown(RuntimeException);fixture.count('legacy_migration_publication_decisions')==0
        when:
        def escaped=journal.inRequest(migration,capability){request->request}
        escaped.authorize()
        then:def closedRequest=thrown(com.arman.bank.authservice.domain.LoginProof.Failure);closedRequest.error()==com.arman.bank.authservice.domain.LoginProof.Error.UNAVAILABLE;fixture.count('legacy_migration_publication_decisions')==0
    }
    def ready(boolean sso=false){def original=admitted(sso);consumed(original,O.Purpose.EMAIL);consumed(original,O.Purpose.PHONE);original}
    def authorize(def original){journal.inRequest(original.admission.migrationId(),original.admission.ownerCapability()){request->request.authorize()}}
    def 'concurrent genuine authorizations converge on one immutable decision and never final authority'(){
        given:
        def original=ready()
        def executor=java.util.concurrent.Executors.newFixedThreadPool(2)
        def start=new java.util.concurrent.CountDownLatch(1)
        def work={start.await();authorize(original)} as java.util.concurrent.Callable
        def first=executor.submit(work);def second=executor.submit(work)
        when:start.countDown();def a=first.get(8,java.util.concurrent.TimeUnit.SECONDS);def b=second.get(8,java.util.concurrent.TimeUnit.SECONDS)
        then:
        a==b
        fixture.count('legacy_migration_publication_decisions')==1
        fixture.count('managed_registration_principals')==0
        fixture.auth.me('Bearer '+original.token).id()==original.identityId
        cleanup:first?.cancel(true);second?.cancel(true);executor?.shutdownNow();executor?.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS)
    }
    def 'lost callback response after real commit resolves exact retained PRIMARY decision without minting again'(){
        given:def original=ready();def q=query(original.admission.migrationId())
        when:
        journal.inRequest(q.migrationId(),original.admission.ownerCapability()){request->request.authorize();throw new IllegalStateException('synthetic response loss after committed authorization')}
        then:thrown(IllegalStateException)
        fixture.count('legacy_migration_publication_decisions')==1
        when:
        int observations=fixture.observerCalls
        def decision=journal.decision(q)
        def proof=journal.evidence(new F.EvidenceQuery(q.migrationId(),UUID.fromString(decision.fields().decision_id),q.stageOperationId(),q.commandId(),q.payloadHash()))
        then:decision.fields().state==F.STATE;proof.fields().state==F.STATE
        fixture.observerCalls==observations
        fixture.count('legacy_migration_publication_decisions')==1
        fixture.auth.me('Bearer '+original.token).id()==original.identityId
        // This is response loss after a known commit, not a claim of ambiguous JDBC COMMIT coverage.
    }
    def 'retained readback after original managed proof expiration does not refresh observations or authorize again'(){
        given:
        fixture.tokenLifetime=15L
        def original=ready();def decision=authorize(original);def q=query(original.admission.migrationId())
        def cutoff=fixture.database.transaction{sql->sql.fetchOne('select token_expires_at from legacy_migration_managed_receipts where migration_id=?',q.migrationId()).get(0,OffsetDateTime).toInstant()}
        long waitBound=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
        while(Instant.now().isBefore(cutoff)&&System.nanoTime()<waitBound)Thread.sleep(20)
        assert !Instant.now().isBefore(cutoff)
        int observations=fixture.observerCalls
        when:def retained=journal.decision(q)
        then:retained==decision;fixture.observerCalls==observations
        when:authorize(original)
        then:thrown(RuntimeException)
        fixture.count('legacy_migration_publication_decisions')==1
        fixture.auth.me('Bearer '+original.token).id()==original.identityId
    }
    def 'deadline crossed by actual journal insert rolls back decision and preserves existing session'(){
        given:
        def original=ready()
        fixture.database.transaction{sql->
            sql.execute("create function finalization_test_delay() returns trigger language plpgsql as 'begin perform pg_sleep(4.25); return NEW; end'")
            sql.execute('create trigger finalization_test_delay after insert on legacy_migration_publication_decisions for each row execute function finalization_test_delay()')
        }
        when:authorize(original)
        then:thrown(RuntimeException)
        fixture.count('legacy_migration_publication_decisions')==0
        fixture.count('managed_registration_principals')==0
        fixture.auth.me('Bearer '+original.token).id()==original.identityId
    }
    def 'missing retained journal key or corrupted HMAC never makes immutable readback positive'(){
        given:
        def original=ready();authorize(original);def q=query(original.admission.migrationId())
        def rotated=new LegacyMigrationManagedProofCrypto('rotated',[rotated:([99]*32) as byte[]])
        def missingKey=new PostgresLegacyMigrationFinalizationStore(fixture.database,otp,fixture.managed,rotated,fixture.ACTOR,true)
        when:missingKey.decision(q)
        then:def absent=thrown(F.Failure);absent.error()==F.Error.UNAVAILABLE
        when:
        // Negative-only corruption fault on a genuine producer row in this fresh isolated DB.
        fixture.database.transaction{sql->sql.execute('alter table legacy_migration_publication_decisions disable trigger user');sql.execute("update legacy_migration_publication_decisions set journal_hash=repeat('0',64) where migration_id=?",q.migrationId());sql.execute('alter table legacy_migration_publication_decisions enable trigger user')}
        journal.decision(q)
        then:def corrupt=thrown(F.Failure);corrupt.error()==F.Error.UNAVAILABLE
        fixture.count('managed_registration_principals')==0
        fixture.auth.me('Bearer '+original.token).id()==original.identityId
    }
}
