package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.domain.LegacyMigrationOtp as O
import com.arman.bank.authservice.domain.LoginProof
import spock.lang.Specification

/** Actual owned PostgreSQL + genuine managed producer + actual User stage.
 * Token/JWKS and delivery remain synthetic; no native/SMTP/banking success claim. */
class LegacyMigrationOtpOwnedRequestIntegrationSpec extends Specification {
    LegacyMigrationFinalizationIntegrationSpec fixture
    def setup(){fixture=new LegacyMigrationFinalizationIntegrationSpec();fixture.setup()}
    def cleanup(){fixture?.cleanup()}
    def prepared(){
        def f=fixture.fixture;def original=f.localOwner();fixture.user.bootstrap(original.identityId,f.PHONE)
        def redirect=f.managed.start(original.admission.migrationId(),original.admission.ownerCapability(),UUID.randomUUID())
        f.interactive(redirect,'legacy_migration_managed_attempts');f.managed.complete(f.callback(redirect));original
    }
    def owned(Closure action){
        def f=fixture.fixture
        new PostgresLoginRequestScope(f.database).inRequest(f.ACTOR){request->
            def before=request.transaction{sql->
                def backend=sql.fetchOne('select pg_backend_pid(),backend_start from pg_stat_activity where pid=pg_backend_pid()')
                [pid:backend.get(0,Integer),start:backend.get(1,java.time.OffsetDateTime),admission:sql.fetchOne('select credits,refilled_at from sso_login_proof_admission where writer_hash=?',([LoginProof.writerHash(f.ACTOR)] as Object[])).intoMap(),seen:sql.fetch('select invocation_id from legacy_migration_managed_observations').getValues(0,UUID).toSet()]
            }
            def result=action.call(request)
            request.transaction{sql->
                assert sql.fetchOne('select credits,refilled_at from sso_login_proof_admission where writer_hash=?',([LoginProof.writerHash(f.ACTOR)] as Object[])).intoMap()==before.admission
                def rows=sql.fetch('select invocation_id,backend_pid,backend_start from legacy_migration_managed_observations').findAll{!before.seen.contains(it.get('invocation_id',UUID))}
                assert !rows.empty
                assert rows.every{it.get('backend_pid',Integer)==before.pid&&it.get('backend_start',java.time.OffsetDateTime)==before.start}
                assert sql.fetchOne('select 1').get(0,Integer)==1
            }
            result
        }
    }
    def 'owned stage issue consume use same backend and no second actor admission with unchanged public replay'(){
        given:
        def original=prepared();def migration=original.admission.migrationId();def cap=original.admission.ownerCapability()
        owned{request->fixture.otp.stage(request,migration,cap,fixture.user.stagePort())}
        UUID issueCommand=UUID.randomUUID(),consumeCommand=UUID.randomUUID()
        when:def challenge=owned{request->fixture.otp.issue(request,migration,cap,issueCommand,purpose)}
        then:challenge.error()==null;challenge.state()=='PENDING_DELIVERY'
        when:
        def lease=fixture.otp.claimDelivery().orElseThrow();def payload=fixture.otpCrypto.decrypt(lease.envelope(),lease.encrypted())
        assert lease.envelope().challengeId()==challenge.challengeId()
        assert fixture.otp.finishDelivery(lease.deliveryId(),lease.fence(),true) // Synthetic delivery only.
        def receipt=owned{request->fixture.otp.consume(request,migration,cap,consumeCommand,challenge.challengeId(),purpose,payload.code())}
        then:receipt.state()=='CONSUMED';receipt.receiptId()!=null
        when:def replay=fixture.otp.inRequest(migration,cap){request->request.consume(consumeCommand,challenge.challengeId(),purpose,payload.code())}
        then:replay==receipt
        fixture.fixture.count('legacy_migration_otp_receipts')==1
        fixture.fixture.count('legacy_migration_publication_decisions')==0
        fixture.fixture.count('managed_identity_sources')==0
        fixture.fixture.auth.me('Bearer '+original.token).id()==original.identityId
        where:purpose << [O.Purpose.EMAIL,O.Purpose.PHONE]
    }
    def 'actual User ACK delayed past original operation cutoff cannot attach under a renewed clock'(){
        given:def original=prepared();def migration=original.admission.migrationId();def cap=original.admission.ownerCapability()
        def delayed={seed->def exchange=fixture.user.stagePort().stage(seed);Thread.sleep(4250);exchange} as com.arman.bank.authservice.application.LegacyMigrationOtpStore.StagePort
        when:new PostgresLoginRequestScope(fixture.fixture.database).inRequest(fixture.fixture.ACTOR){request->fixture.otp.stage(request,migration,cap,delayed)}
        then:thrown(RuntimeException)
        fixture.fixture.count('legacy_migration_otp_stage_acks')==0
        fixture.fixture.count('legacy_migration_otp_challenges')==0
        fixture.fixture.count('legacy_migration_otp_outbox')==0
        fixture.fixture.count('legacy_migration_otp_receipts')==0
        fixture.fixture.auth.me('Bearer '+original.token).id()==original.identityId
    }
    def 'owned callback lifetime closes escaped request without closing caller session'(){
        given:def original=prepared();def migration=original.admission.migrationId();def cap=original.admission.ownerCapability()
        when:
        new PostgresLoginRequestScope(fixture.fixture.database).inRequest(fixture.fixture.ACTOR){request->
            def escaped=fixture.otp.inOwnedRequest(request,migration,cap){inner->inner}
            int observations=fixture.fixture.observerCalls
            try{escaped.stageSeed();throw new AssertionError('escaped request accepted')}catch(O.Failure rejected){assert rejected.error()==O.Error.UNAVAILABLE}
            assert fixture.fixture.observerCalls==observations
            assert request.transaction{sql->sql.fetchOne('select 1').get(0,Integer)}==1
        }
        then:fixture.fixture.count('legacy_migration_otp_stages')==0
    }
    def 'wrong actor and cross thread owned access fail before current provider work'(){
        given:def original=prepared();def migration=original.admission.migrationId();def cap=original.admission.ownerCapability()
        int observations=fixture.fixture.observerCalls
        when:new PostgresLoginRequestScope(fixture.fixture.database).inRequest('other-migration-actor'){request->fixture.otp.inOwnedRequest(request,migration,cap){inner->inner.stageSeed()}}
        then:def actorFailure=thrown(O.Failure);actorFailure.error()==O.Error.UNAVAILABLE
        fixture.fixture.observerCalls==observations
        when:
        def executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        new PostgresLoginRequestScope(fixture.fixture.database).inRequest(fixture.fixture.ACTOR){request->
            fixture.otp.inOwnedRequest(request,migration,cap){inner->
                def task=executor.submit({try{inner.stageSeed();null}catch(RuntimeException failure){failure}} as java.util.concurrent.Callable)
                def failure=task.get(3,java.util.concurrent.TimeUnit.SECONDS)
                assert failure instanceof LoginProof.Failure
                assert failure.error()==LoginProof.Error.UNAVAILABLE
                null
            }
        }
        then:fixture.fixture.observerCalls==observations
        fixture.fixture.count('legacy_migration_otp_stages')==0
        cleanup:executor?.shutdownNow();executor?.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS)
    }
    def 'public and owned malformed capability reject before another admission or provider call'(){
        given:
        def original=prepared();def migration=original.admission.migrationId()
        def before=fixture.fixture.database.transaction{sql->sql.fetch('select credits,refilled_at from sso_login_proof_admission order by writer_hash').intoMaps()}
        int observations=fixture.fixture.observerCalls
        when:fixture.otp.inRequest(migration,capability){request->throw new AssertionError('malformed capability admitted')}
        then:def publicFailure=thrown(O.Failure);publicFailure.error()==O.Error.UNAUTHORIZED
        when:fixture.otp.inOwnedRequest(null,migration,capability){request->throw new AssertionError('malformed capability admitted')}
        then:def ownedFailure=thrown(O.Failure);ownedFailure.error()==O.Error.UNAUTHORIZED
        fixture.fixture.observerCalls==observations
        fixture.fixture.database.transaction{sql->sql.fetch('select credits,refilled_at from sso_login_proof_admission order by writer_hash').intoMaps()}==before
        where:capability << [null,'','short',('a'*44)]
    }
}
