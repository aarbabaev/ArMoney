package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.domain.LegacyMigrationFinalization as F
import spock.lang.Specification

/** Actual User process + actual Auth decision routes and real producer-owned PG receipts.
 * Reuses explicit synthetic token/JWKS/OTP delivery fixture; no genuine Keycloak/SMTP/native claim. */
class MigrationFinalAckIntegrationSpec extends Specification {
    private static final class SyntheticFinalConsumerFailure extends RuntimeException {
        SyntheticFinalConsumerFailure(){super('synthetic downstream final consumer failure')}
    }
    private static void assertImmutable(Map value){
        boolean rejected=false
        try{value.put('synthetic_mutation',true)}catch(UnsupportedOperationException expected){rejected=true}
        assert rejected
    }
    LegacyMigrationFinalizationIntegrationSpec fixture
    io.javalin.Javalin authRead
    PostgresMigrationFinalAckStore producer
    def setup(){fixture=new LegacyMigrationFinalizationIntegrationSpec();fixture.setup()}
    def cleanup(){producer?.close();authRead?.stop();fixture?.cleanup()}
    def 'actual authenticated User publication persists original twenty field ACK and replay never promotes or revokes'(){
        given:
        def original=fixture.ready(sso)
        def decision=fixture.authorize(original)
        def settings=fixture.user.syntheticPublicationSettings()
        int port=fixture.unavailableAuth.address.port
        fixture.unavailableAuth.stop(0);fixture.unavailableAuth=null
        def routes=new LegacyMigrationFinalizationRoutes(fixture.journal,settings.decisionKey(),true)
        authRead=io.javalin.Javalin.create{config->routes.configure(config)}.start(port)
        def managed=fixture.fixture
        def consumer=new PostgresMigrationBankingEligibilityStore(managed.crypto)
        def wrong=PostgresMigrationFinalAckStore.synthetic(managed.database,managed.custody,managed.crypto,fixture.journal,fixture.otp,managed.managed,consumer,managed.ACTOR,'x'*32,settings.origin())
        when:wrong.publish(original.admission.migrationId(),original.admission.ownerCapability())
        then:thrown(F.Failure)
        managed.count('legacy_migration_verified_final_acks')==0
        when:
        wrong.close()
        producer=PostgresMigrationFinalAckStore.synthetic(managed.database,managed.custody,managed.crypto,fixture.journal,fixture.otp,managed.managed,consumer,managed.ACTOR,settings.publicationKey(),settings.origin())
        def ack=producer.publish(original.admission.migrationId(),original.admission.ownerCapability())
        def before=managed.database.transaction{sql->sql.fetch('select row_to_json(t)::text from legacy_migration_verified_final_acks t').getValues(0,String)}
        then:
        ack.keySet().toList()==PostgresMigrationFinalAckStore.FINAL
        ack.decision_id==decision.fields().decision_id
        ack.identity_id==original.identityId.toString()
        ack.publication_revision==1L
        managed.count('legacy_migration_verified_final_acks')==1
        managed.count('legacy_migration_final_completions')==0
        managed.count('managed_identity_sources')==0
        managed.auth.me('Bearer '+original.token).id()==original.identityId
        when:
        def replay=producer.publicationResult(original.admission.migrationId(),original.admission.ownerCapability())
        then:replay==ack
        managed.database.transaction{sql->sql.fetch('select row_to_json(t)::text from legacy_migration_verified_final_acks t').getValues(0,String)}==before
        when:
        def reserved=new PostgresLoginRequestScope(managed.database).inRequest(managed.ACTOR){owned->producer.reserveFinalCommand(owned,original.admission.migrationId(),original.admission.ownerCapability())}
        def commandBefore=managed.database.transaction{sql->sql.fetch('select row_to_json(t)::text from legacy_migration_final_commands t').getValues(0,String)}
        def reservedAgain=new PostgresLoginRequestScope(managed.database).inRequest(managed.ACTOR){owned->producer.reserveFinalCommand(owned,original.admission.migrationId(),original.admission.ownerCapability())}
        then:
        reservedAgain==reserved
        managed.count('legacy_migration_final_commands')==1
        managed.database.transaction{sql->sql.fetch('select row_to_json(t)::text from legacy_migration_final_commands t').getValues(0,String)}==commandBefore
        managed.count('managed_identity_sources')==0
        when:
        def sessionsBefore=managed.database.transaction{sql->sql.fetch('select row_to_json(t)::text from sessions t order by token_hash').getValues(0,String)}
        boolean callbackObserved=false
        boolean rollbackWriteObserved=false
        def downstreamFailure=new SyntheticFinalConsumerFailure()
        new PostgresLoginRequestScope(managed.database).inRequest(managed.ACTOR){owned->
            producer.withFinalDecision(owned,original.admission.migrationId(),original.admission.ownerCapability(),reserved,{sql,d,a,e->
                d.requireCurrent(sql);a.requireCurrent(sql);e.requireObservation(sql)
                def journal=d.journal()
                assert journal.migration_id==original.admission.migrationId()
                assert journal.identity_id==original.identityId
                assert journal.decision_id.toString()==decision.fields().decision_id
                assert d.finalCommandId()==reserved
                assert d.finalCommandHash().length==32
                assert a.acknowledgement()==ack
                assert a.journalHash()==journal.journal_hash
                assert e.receiptId()==journal.managed_receipt_id
                assertImmutable(journal);assertImmutable(a.acknowledgement())
                callbackObserved=true
                // Probe actual rollback inside the downstream transaction, without promotion.
                assert sql.execute('delete from sessions where identity_id=?',original.identityId)==sessionsBefore.size()
                assert sql.fetchOne('select count(*) from sessions where identity_id=?',original.identityId).get(0,Long)==0L
                rollbackWriteObserved=true
                throw downstreamFailure
            } as PostgresMigrationFinalAckStore.FinalWork)
        }
        then:
        def failed=thrown(SyntheticFinalConsumerFailure)
        failed.is(downstreamFailure)
        callbackObserved
        rollbackWriteObserved
        managed.database.transaction{sql->sql.fetch('select row_to_json(t)::text from sessions t order by token_hash').getValues(0,String)}==sessionsBefore
        managed.database.transaction{sql->sql.fetch('select row_to_json(t)::text from legacy_migration_verified_final_acks t').getValues(0,String)}==before
        managed.database.transaction{sql->sql.fetch('select row_to_json(t)::text from legacy_migration_final_commands t').getValues(0,String)}==commandBefore
        managed.count('legacy_migration_final_completions')==0
        managed.count('managed_identity_sources')==0
        managed.count('migration_banking_authority_current')==0
        managed.count('migration_banking_authorities')==0
        managed.count('migration_banking_grants')==0
        managed.auth.me('Bearer '+original.token).id()==original.identityId
        cleanup:wrong?.close()
        where:sso << [false,true]
    }
    def 'lost Auth ACK persistence recovers actual User result after proof expiry without renewal or cutover'(){
        given:
        fixture.fixture.tokenLifetime=15L
        def original=fixture.ready(false);fixture.authorize(original)
        def settings=fixture.user.syntheticPublicationSettings()
        int port=fixture.unavailableAuth.address.port
        fixture.unavailableAuth.stop(0);fixture.unavailableAuth=null
        def routes=new LegacyMigrationFinalizationRoutes(fixture.journal,settings.decisionKey(),true)
        authRead=io.javalin.Javalin.create{config->routes.configure(config)}.start(port)
        def managed=fixture.fixture
        producer=PostgresMigrationFinalAckStore.synthetic(managed.database,managed.custody,managed.crypto,fixture.journal,fixture.otp,managed.managed,new PostgresMigrationBankingEligibilityStore(managed.crypto),managed.ACTOR,settings.publicationKey(),settings.origin())
        managed.database.transaction{sql->sql.execute("create function final_ack_test_fault() returns trigger language plpgsql as 'begin raise exception ''synthetic final ACK persistence fault''; end'");sql.execute('create trigger final_ack_test_fault before insert on legacy_migration_verified_final_acks for each row execute function final_ack_test_fault()')}
        when:producer.publish(original.admission.migrationId(),original.admission.ownerCapability())
        then:thrown(RuntimeException)
        managed.count('legacy_migration_verified_final_acks')==0
        managed.count('legacy_migration_publication_decisions')==1
        managed.auth.me('Bearer '+original.token).id()==original.identityId
        when:
        managed.database.transaction{sql->sql.execute('drop trigger final_ack_test_fault on legacy_migration_verified_final_acks');sql.execute('drop function final_ack_test_fault()')}
        def cutoff=managed.database.transaction{sql->sql.fetchOne('select token_expires_at from legacy_migration_managed_receipts where migration_id=?',original.admission.migrationId()).get(0,java.time.OffsetDateTime).toInstant()}
        long bound=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
        while(java.time.Instant.now().isBefore(cutoff)&&System.nanoTime()<bound)Thread.sleep(20)
        assert !java.time.Instant.now().isBefore(cutoff)
        int observations=managed.observerCalls
        def ack=producer.publicationResult(original.admission.migrationId(),original.admission.ownerCapability())
        then:
        ack.publication_revision==1L
        managed.count('legacy_migration_verified_final_acks')==1
        managed.count('legacy_migration_final_completions')==0
        managed.count('managed_identity_sources')==0
        managed.observerCalls==observations
        managed.auth.me('Bearer '+original.token).id()==original.identityId
        when:
        new PostgresLoginRequestScope(managed.database).inRequest(managed.ACTOR){owned->producer.reserveFinalCommand(owned,original.admission.migrationId(),original.admission.ownerCapability())}
        then:thrown(RuntimeException)
        managed.count('legacy_migration_final_commands')==0
        managed.count('managed_identity_sources')==0
    }
    def 'actual publication transport bounds stalled dripping and oversized User responses and releases all resources'(){
        given:
        def original=fixture.ready(false);fixture.authorize(original)
        def managed=fixture.fixture;def settings=fixture.user.syntheticPublicationSettings()
        def peer=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        def threads=java.util.concurrent.Executors.newFixedThreadPool(2);peer.executor=threads
        def entered=new java.util.concurrent.CountDownLatch(1)
        def disconnected=new java.util.concurrent.CountDownLatch(1)
        def wrongRole=new java.util.concurrent.atomic.AtomicBoolean(false)
        peer.createContext('/v1/internal/migrations/legacy/user-publish'){exchange->
            try{
                if(exchange.requestMethod!='POST'||exchange.requestHeaders.getFirst('X-ArMoney-Migration-Publication-Key')!=settings.publicationKey()||exchange.requestHeaders.getFirst('X-ArMoney-Migration-Decision-Key')!=null||exchange.requestHeaders.getFirst('X-ArMoney-Migration-Stage-Key')!=null||exchange.requestHeaders.getFirst('Authorization')!=null||exchange.requestHeaders.getFirst('X-ArMoney-Auth-Status-Key')!=null||exchange.requestHeaders.getFirst('X-Service-Key')!=null)wrongRole.set(true)
                exchange.requestBody.readNBytes(12289)
                exchange.responseHeaders.set('Content-Type','application/json')
                exchange.sendResponseHeaders(200,mode=='oversized'?12289:12000)
                entered.countDown()
                if(mode=='oversized'){exchange.responseBody.write(new byte[12289]);exchange.responseBody.flush()}
                else{
                    if(mode=='stall')Thread.sleep(4500)
                    // Keep the drip peer live beyond the client's four-second cutoff so it probes cancellation.
                    long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(mode=='drip'?8:4)
                    while(System.nanoTime()<end){exchange.responseBody.write(32);exchange.responseBody.flush();Thread.sleep(mode=='drip'?200:50)}
                }
            }catch(IOException physicalClose){disconnected.countDown()}
            catch(InterruptedException cancelled){Thread.currentThread().interrupt()}
            finally{try{exchange.close()}catch(Exception ignored){}}
        }
        peer.start()
        producer=PostgresMigrationFinalAckStore.synthetic(managed.database,managed.custody,managed.crypto,fixture.journal,fixture.otp,managed.managed,new PostgresMigrationBankingEligibilityStore(managed.crypto),managed.ACTOR,settings.publicationKey(),URI.create('http://127.0.0.1:'+peer.address.port))
        def permits=PostgresMigrationFinalAckStore.getDeclaredField('calls');permits.accessible=true
        long started=System.nanoTime()
        when:producer.publish(original.admission.migrationId(),original.admission.ownerCapability())
        then:
        def unavailable=thrown(F.Failure);unavailable.error()==F.Error.UNAVAILABLE
        long elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)
        entered.count==0
        !wrongRole.get()
        elapsed<6500
        mode=='oversized'||elapsed>=2500
        mode=='oversized'||disconnected.await(4,java.util.concurrent.TimeUnit.SECONDS)
        ['pending','bodies'].every{name->def field=PostgresMigrationFinalAckStore.getDeclaredField(name);field.accessible=true;field.get(producer).empty}
        permits.get(producer).availablePermits()==16
        managed.count('legacy_migration_verified_final_acks')==0
        managed.count('legacy_migration_final_commands')==0
        managed.count('legacy_migration_final_completions')==0
        managed.count('managed_identity_sources')==0
        fixture.journal.decision(fixture.query(original.admission.migrationId())).fields().state=='PUBLICATION_AUTHORIZED'
        managed.auth.me('Bearer '+original.token).id()==original.identityId
        cleanup:peer?.stop(0);threads?.shutdownNow();threads?.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS)
        where:mode << ['stall','drip','oversized']
    }
}
