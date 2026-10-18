package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.AuthFailure
import com.arman.bank.authservice.domain.LegacyMigrationFinalization as F
import com.arman.bank.authservice.domain.LoginProof
import com.arman.bank.authservice.domain.CodeConfirmation
import java.time.*
import spock.lang.Specification

/** Actual Auth/real User publication and genuine OTP issue/consume use cases.
 * OTP delivery ACK is the existing explicitly controlled synthetic fixture; no real email/SMS claim.
 * Provider token/JWKS/status peers are synthetic; this is not native Keycloak acceptance.
 * Actual UserMain live-current observation and post-FINAL user-resolve are required; no readiness flag or fake User tuple is supplied. */
class MigrationSourcePromotionIntegrationSpec extends Specification {
    LegacyMigrationFinalizationIntegrationSpec fixture
    io.javalin.Javalin authRead
    PostgresMigrationFinalAckStore producer
    PostgresMigrationBankingEligibilityStore consumer
    HttpMigrationUserCurrent currentUser
    def setup(){
        fixture=new LegacyMigrationFinalizationIntegrationSpec();fixture.setup()
        // Reconfigure only the isolated synthetic fixture, before any owner/bootstrap/stage.
        fixture.user.close()
        def repository=java.nio.file.Path.of('').toAbsolutePath();if(!java.nio.file.Files.isDirectory(repository.resolve('user-service')))repository=repository.parent
        fixture.user=LegacyMigrationUserStageFixture.launchWithLiveCurrent(repository,fixture.userPg,URI.create('http://127.0.0.1:'+fixture.unavailableAuth.address.port))
        fixture.service=new com.arman.bank.authservice.application.LegacyMigrationOtpService(fixture.otp,fixture.user.stagePort())
        def settings=fixture.user.syntheticCurrentSettings()
        def config=new MigrationUserRevisionConfiguration(true,URI.create('http://user-service:8080'),settings.currentWriter(),settings.currentKey(),null,settings.mutationWriter(),settings.mutationKey())
        currentUser=new HttpMigrationUserCurrent(config,settings.origin(),HttpCodeSessionObservations.privateClient())
    }
    def cleanup(){currentUser?.close();producer?.close();authRead?.stop();fixture?.cleanup()}

    private Map prepared(boolean sso,boolean distinctAssociation=false){
        def original
        if(distinctAssociation){
            assert sso
            def managed=fixture.fixture
            original=managed.historicalSsoOwner()
            managed.database.transaction{sql->
                PostgresVerificationProjectionStore.lockControls(sql)
                PostgresLegacyMigrationStore.lockOwner(sql,original.identityId)
                // Real delete/recreate executes the published association generation triggers.
                // No generation counter is rewritten to match an expected value.
                sql.execute('delete from external_identities where issuer=? and subject=?',managed.ISSUER,'signed-existing')
                sql.execute('insert into external_identities(issuer,subject,identity_id) values(?,?,?)',managed.ISSUER,'signed-existing',original.identityId)
            }
            fixture.user.bootstrap(original.identityId,managed.PHONE)
            def account=managed.account.start(UUID.randomUUID())
            managed.interactive(account,'legacy_migration_account_attempts')
            original.admission=managed.ssoAdmissionWithCandidate(account)
            def target=managed.managed.start(original.admission.migrationId(),original.admission.ownerCapability(),UUID.randomUUID())
            managed.interactive(target,'legacy_migration_managed_attempts')
            managed.managed.complete(managed.callback(target))
            fixture.service.stage(original.admission.migrationId(),original.admission.ownerCapability())
            fixture.consumed(original,com.arman.bank.authservice.domain.LegacyMigrationOtp.Purpose.EMAIL)
            fixture.consumed(original,com.arman.bank.authservice.domain.LegacyMigrationOtp.Purpose.PHONE)
        }else original=fixture.ready(sso)
        def decision=fixture.authorize(original)
        def settings=fixture.user.syntheticPublicationSettings()
        int port=fixture.unavailableAuth.address.port
        fixture.unavailableAuth.stop(0);fixture.unavailableAuth=null
        def routes=new LegacyMigrationFinalizationRoutes(fixture.journal,settings.decisionKey(),true)
        authRead=io.javalin.Javalin.create{config->routes.configure(config)}.start(port)
        def managed=fixture.fixture
        consumer=new PostgresMigrationBankingEligibilityStore(managed.crypto)
        producer=PostgresMigrationFinalAckStore.synthetic(managed.database,managed.custody,managed.crypto,fixture.journal,fixture.otp,managed.managed,consumer,managed.ACTOR,settings.publicationKey(),settings.origin())
        producer.publish(original.admission.migrationId(),original.admission.ownerCapability())
        def command=new PostgresLoginRequestScope(managed.database).inRequest(managed.ACTOR){owned->producer.reserveFinalCommand(owned,original.admission.migrationId(),original.admission.ownerCapability())}
        [original:original,decision:decision,command:command]
    }
    private void promote(Map prepared){
        def managed=fixture.fixture
        new PostgresLoginRequestScope(managed.database).inRequest(managed.ACTOR){owned->
            producer.withFinalDecision(owned,prepared.original.admission.migrationId(),prepared.original.admission.ownerCapability(),prepared.command,
                {sql,decision,ack,evidence->consumer.promote(sql,decision,ack,evidence)} as PostgresMigrationFinalAckStore.FinalWork)
        }
        // Real User PRIMARY resolves Auth's committed, HMAC-verified completion before RELEASED.
        // This is deliberately after FINAL; a rollback above never reaches this HTTP call.
        resolveUser(prepared)
    }
    private void resolveUser(Map prepared){
        def managed=fixture.fixture
        Map journal=managed.database.transaction{sql->
            PostgresVerificationProjectionStore.lockControls(sql)
            PostgresLegacyMigrationFinalizationStore.requireHistoricalJournal(sql,prepared.original.admission.migrationId(),managed.crypto)
        }
        Map tuple=[:]
        ['migration_id','decision_id','stage_operation_id'].each{key->tuple[key]=journal[key].toString()}
        tuple.command_id=journal.publication_command_id.toString();tuple.payload_hash=journal.payload_hash
        def settings=fixture.user.syntheticPublicationSettings()
        def client=java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()
        def pending
        try{
            byte[] body=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(tuple)
            def request=java.net.http.HttpRequest.newBuilder(settings.origin().resolve('/v1/internal/migrations/legacy/user-resolve')).timeout(Duration.ofSeconds(4))
                .header('Content-Type','application/json').header('X-ArMoney-Migration-Publication-Key',settings.publicationKey()).POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)).build()
            pending=client.sendAsync(request,{info->new BoundedReply()} as java.net.http.HttpResponse.BodyHandler<byte[]>)
            def reply=pending.get(4,java.util.concurrent.TimeUnit.SECONDS)
            assert reply.statusCode()==200:'Actual User resolution did not accept committed FINAL'
            Map response=LegacyMigrationFinalizationRoutes.parse(reply.body())
            boolean bound=response.migration_id==tuple.migration_id&&response.decision_id==tuple.decision_id&&response.state=='RELEASED_AFTER_COMPLETION'
            assert bound:'Actual User resolution did not release the exact original baseline'
        }finally{pending?.cancel(true);client.shutdownNow();assert client.awaitTermination(Duration.ofSeconds(2))}
    }
    private static final class BoundedReply implements java.net.http.HttpResponse.BodySubscriber<byte[]> {
        final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>()
        final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()
        java.util.concurrent.Flow.Subscription subscription
        boolean ended
        java.util.concurrent.CompletionStage<byte[]> getBody(){result}
        synchronized void onSubscribe(java.util.concurrent.Flow.Subscription incoming){if(ended||subscription!=null){incoming.cancel();return};subscription=incoming;incoming.request(1)}
        synchronized void onNext(List<java.nio.ByteBuffer> items){if(ended)return;for(def item:items){if(item.remaining()>4096-bytes.size()){fail();return};byte[] part=new byte[item.remaining()];item.get(part);bytes.writeBytes(part)};subscription.request(1)}
        synchronized void onError(Throwable ignored){fail()}
        synchronized void onComplete(){if(!ended){ended=true;result.complete(bytes.toByteArray());bytes.reset()}}
        private void fail(){if(!ended){ended=true;subscription?.cancel();bytes.reset();result.completeExceptionally(new IllegalStateException('User resolve response unavailable'))}}
    }
    def 'genuine final cutover preserves owner and historical completion while denying original legacy session'(){
        given:
        def prepared=prepared(sso)
        def managed=fixture.fixture
        UUID owner=prepared.original.identityId
        when:
        promote(prepared)
        def result=managed.database.transaction{sql->
            PostgresVerificationProjectionStore.lockControls(sql)
            def journal=PostgresLegacyMigrationFinalizationStore.requireHistoricalJournal(sql,prepared.original.admission.migrationId(),managed.crypto)
            def completed=PostgresMigrationFinalAckStore.validatedCompletion(sql,journal,managed.crypto,managed.ACTOR).present
            def pointer=sql.fetchOne('select identity_id,current_epoch,current_source_revision from migration_banking_authority_current where identity_id=?',owner)
            [completed:completed,owner:pointer?.get('identity_id',UUID),epoch:pointer?.get('current_epoch',Long),revision:pointer?.get('current_source_revision',Long)]
        }
        then:
        result.completed
        result.owner==owner
        result.epoch==1L
        result.revision==1L
        managed.count('managed_identity_sources')==1
        managed.count('migration_banking_authorities')==1
        managed.count('migration_banking_grants')==1
        managed.count('legacy_migration_final_completions')==1
        when:
        managed.auth.me('Bearer '+prepared.original.token)
        then:
        def denied=thrown(AuthFailure)
        denied.kind()==AuthFailure.Kind.UNAUTHORIZED
        where:
        sso << [false,true]
    }
    def 'actual grant insert failure rolls back every cutover write and preserves original session'(){
        given:
        def prepared=prepared(false)
        def managed=fixture.fixture
        managed.database.transaction{sql->
            sql.execute('create sequence migration_promotion_fault_witness')
            sql.execute("create function migration_promotion_fault() returns trigger language plpgsql as 'begin perform nextval(''migration_promotion_fault_witness''); raise exception ''synthetic promotion rollback''; end'")
            sql.execute('create trigger migration_promotion_fault after insert on migration_banking_grants for each row execute function migration_promotion_fault()')
        }
        when:
        promote(prepared)
        then:
        thrown(RuntimeException)
        when:
        boolean reached=managed.database.transaction{sql->sql.fetchOne('select is_called from migration_promotion_fault_witness').get(0,Boolean)}
        def survivingOwner=managed.auth.me('Bearer '+prepared.original.token).id()
        then:
        reached
        survivingOwner==prepared.original.identityId
        managed.count('managed_identity_sources')==0
        managed.count('migration_banking_authority_current')==0
        managed.count('migration_banking_authorities')==0
        managed.count('migration_banking_grants')==0
        managed.count('legacy_migration_final_completions')==0
        managed.count('legacy_migration_verified_final_acks')==1
        managed.count('legacy_migration_final_commands')==1
    }
    private LoginProof.Binding nativeBinding(Map context){
        def managed=fixture.fixture
        def tuple=managed.database.transaction{sql->sql.fetchOne('select issuer,subject from legacy_migration_managed_receipts where migration_id=?',context.original.admission.migrationId()).intoMap()}
        new LoginProof.Binding(tuple.issuer as String,tuple.subject as String,'managed-client','synthetic-native-session')
    }
    private void nativeEvidence(LoginProof.Binding binding){
        def managed=fixture.fixture
        new PostgresLoginRequestScope(managed.database).inRequest(managed.ACTOR){owned->
            long monotonicStart=System.nanoTime()
            def admission=owned.transaction{sql->[start:sql.fetchOne('select clock_timestamp()').get(0,OffsetDateTime).toInstant(),source:SourceEligibilitySql.requireCurrent(sql,binding.issuer(),binding.subject(),managed.crypto)]}
            Instant started=admission.start
            def facts=managed.observer.observe(binding,started,Duration.ofSeconds(4))
            def user=SourceEligibilitySql.observeUser(owned,admission.source,UUID.randomUUID(),started,monotonicStart,currentUser)
            boolean complete=owned.transaction{sql->new PostgresBankingEligibilityStore(sql,managed.crypto).requireMigrationForProof(binding,facts,started,user).completeFor(binding,sql.fetchOne('select clock_timestamp()').get(0,OffsetDateTime).toInstant())}
            assert complete
        }
    }
    def 'passwordless SSO distinct credential and association generations pass native evidence and own suspension guard'(){
        given:
        def context=prepared(true,true)
        def managed=fixture.fixture
        promote(context)
        def binding=nativeBinding(context)
        def counters=managed.database.transaction{sql->
            def row=sql.fetchOne('select g.credential_generation,a.generation,i.password_hash from legacy_auth_generations g join identities i on i.id=g.identity_id join legacy_auth_association_generations a on a.identity_id=g.identity_id where g.identity_id=? and a.issuer=? and a.subject=?',context.original.identityId,binding.issuer(),binding.subject())
            [credential:row.get('credential_generation',Long),association:row.get('generation',Long),passwordAbsent:row.get('password_hash')==null]
        }
        when:
        nativeEvidence(binding)
        then:
        counters.passwordAbsent
        counters.credential==0L
        counters.association>0L
        counters.association!=counters.credential
        when:
        managed.negative=true
        new PostgresLoginRequestScope(managed.database).inRequest(managed.ACTOR){owned->
            long monotonicStart=System.nanoTime()
            def admission=owned.transaction{sql->[start:sql.fetchOne('select clock_timestamp()').get(0,OffsetDateTime).toInstant(),source:SourceEligibilitySql.requireCurrent(sql,binding.issuer(),binding.subject(),managed.crypto)]}
            Instant started=admission.start
            def facts=managed.observer.observe(binding,started,Duration.ofSeconds(4))
            def user=SourceEligibilitySql.observeUser(owned,admission.source,UUID.randomUUID(),started,monotonicStart,currentUser)
            owned.transaction{sql->
                user.require(sql,true,managed.crypto)
                Instant deadline=CodeConfirmation.deadline(binding,facts,started,sql.fetchOne('select clock_timestamp()').get(0,OffsetDateTime).toInstant())
                assert !facts.enabled()
                def current=SourceEligibilitySql.requireCurrent(sql,binding.issuer(),binding.subject(),managed.crypto)
                SourceEligibilitySql.suspend(sql,current,'PROVIDER_NEGATIVE',managed.crypto)
                SourceEligibilitySql.requireOwnSuspension(sql,current)
                user.require(sql,false,managed.crypto)
                CodeConfirmation.fresh(started,deadline,sql.fetchOne('select clock_timestamp()').get(0,OffsetDateTime).toInstant())
            }
        }
        def suspended=managed.database.transaction{sql->sql.fetchOne('select a.state,p.current_epoch,p.current_source_revision from migration_banking_authority_current p join migration_banking_authorities a on a.identity_id=p.identity_id and a.decision_id=p.decision_id and a.verification_epoch=p.current_epoch and a.source_revision=p.current_source_revision where p.identity_id=?',context.original.identityId).intoMap()}
        then:
        suspended.state=='SUSPENDED'
        suspended.current_epoch==2L
        suspended.current_source_revision==2L
        managed.count('migration_banking_authorities')==2
        managed.count('migration_banking_grants')==1
        when:
        managed.negative=false
        nativeEvidence(binding)
        then:
        thrown(RuntimeException)
    }
    def 'proof mode generation mutation denies fresh native evidence without suspending from a stale local snapshot'(){
        given:
        def context=prepared(sso,sso)
        def managed=fixture.fixture
        promote(context)
        def binding=nativeBinding(context)
        def replacement=sso?null:new Argon2Passwords().hash('synthetic replacement migration password')
        managed.database.transaction{sql->
            PostgresVerificationProjectionStore.lockControls(sql)
            PostgresLegacyMigrationStore.lockOwner(sql,context.original.identityId)
            if(sso){
                sql.execute('delete from external_identities where issuer=? and subject=?',binding.issuer(),binding.subject())
                sql.execute('insert into external_identities(issuer,subject,identity_id) values(?,?,?)',binding.issuer(),binding.subject(),context.original.identityId)
            }else sql.execute('update identities set password_hash=? where id=?',replacement,context.original.identityId)
        }
        when:
        nativeEvidence(binding)
        then:
        thrown(RuntimeException)
        managed.count('migration_banking_authorities')==1
        managed.count('migration_banking_grants')==1
        where:
        sso << [false,true]
    }

}
