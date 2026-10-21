package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.LegacyMigrationBrowserService as B
import com.arman.bank.authservice.domain.LegacyMigrationBrowserContinuation as C
import com.arman.bank.authservice.domain.LegacyMigrationOtp as O
import spock.lang.Specification

/** Actual PG, genuine producer RSA/12 HTTP, real User stage. OTP delivery is synthetic.
 * Internal command initialization follows the actual completed managed producer; no receipt/authority seed. */
class LegacyMigrationBrowserContinuationIntegrationSpec extends Specification {
    LegacyMigrationFinalizationIntegrationSpec fixture
    PostgresMigrationFinalAckStore publication
    PostgresLegacyMigrationBrowserCommandStore commands
    PostgresLegacyMigrationBrowserService browser
    def original
    String cookie
    UUID migration
    def setup(){
        fixture=new LegacyMigrationFinalizationIntegrationSpec();fixture.setup()
        original=fixture.admitted(false);cookie=original.admission.ownerCapability();migration=original.admission.migrationId()
        def f=fixture.fixture
        commands=new PostgresLegacyMigrationBrowserCommandStore(f.crypto,f.custody,f.ACTOR)
        def consumer=new PostgresMigrationBankingEligibilityStore(f.crypto)
        publication=new PostgresMigrationFinalAckStore(f.database,f.custody,f.crypto,fixture.journal,fixture.otp,f.managed,consumer,f.ACTOR,'p'*32)
        def services=new PostgresLegacyMigrationBrowserService.ContinuationServices(fixture.otp,fixture.user.stagePort(),fixture.journal,publication,consumer,f.custody)
        browser=new PostgresLegacyMigrationBrowserService(f.database,f.auth,f.local,f.account,f.managed,f.crypto,URI.create(f.account.settings().issuer()+'/protocol/openid-connect/auth'),URI.create(f.managed.settings().issuer()+'/protocol/openid-connect/auth'),true,services)
    }
    def cleanup(){publication?.close();fixture?.cleanup()}
    def owned(Closure work){def f=fixture.fixture;new PostgresLoginRequestScope(f.database).inRequest(f.ACTOR){request->work.call(request)}}
    def begin(){owned{request->commands.begin(request,migration,cookie)}}
    def staged(){def page=begin();def form=page.forms().first();browser.stage(cookie,form.csrf(),form.commandId())}
    def issued(){def page=staged();def form=page.forms().first();browser.issue(cookie,form.csrf(),form.commandId())}
    def form(def page,B.Action action){page.forms().find{it.action()==action}}
    def delivered(){
        def lease=fixture.otp.claimDelivery().orElseThrow();def payload=fixture.otpCrypto.decrypt(lease.envelope(),lease.encrypted())
        assert fixture.otp.finishDelivery(lease.deliveryId(),lease.fence(),true) // synthetic delivery ACK only
        payload.code()
    }
    def count(String table){fixture.fixture.count(table)}
    def 'genuine channel consumes yield distinct receipts and immutable bundles without migration promotion'(){
        given:def email=issued();def emailConsume=form(email,B.Action.EMAIL_CONSUME);def emailIssue=form(email,B.Action.EMAIL_ISSUE)
        String emailCode=delivered()
        when:def phoneIssue=browser.consume(cookie,emailConsume.csrf(),emailConsume.commandId(),emailCode)
        then:phoneIssue.forms()*.action()==[B.Action.PHONE_ISSUE]
        when:browser.issue(cookie,emailIssue.csrf(),emailIssue.commandId())
        then:def obsolete=thrown(B.Failure);obsolete.kind()==B.Kind.CONFLICT;!obsolete.clearCookies()
        when:
        def p=phoneIssue.forms().first();def phone=browser.issue(cookie,p.csrf(),p.commandId());String phoneCode=delivered();def consume=form(phone,B.Action.PHONE_CONSUME)
        def decision=browser.consume(cookie,consume.csrf(),consume.commandId(),phoneCode)
        then:decision.forms()*.action()==[B.Action.DECISION]
        count('legacy_migration_otp_receipts')==2
        count('legacy_migration_publication_decisions')==0
        count('managed_identity_sources')==0
        fixture.fixture.auth.me('Bearer '+original.token).id()==original.identityId
        when:def historical=browser.consume(cookie,emailConsume.csrf(),emailConsume.commandId(),emailCode)
        then:historical==phoneIssue
        count('legacy_migration_otp_receipts')==2
    }
    def 'wrong consume retains original challenge and resend sibling and changed payload conflicts'(){
        given:def page=issued();def consume=form(page,B.Action.EMAIL_CONSUME);def resend=form(page,B.Action.EMAIL_ISSUE);String code=delivered();String wrong=code=='000000'?'000001':'000000'
        when:def retry=browser.consume(cookie,consume.csrf(),consume.commandId(),wrong)
        then:form(retry,B.Action.EMAIL_ISSUE)==resend
        form(retry,B.Action.EMAIL_CONSUME).attemptsRemaining()==4
        count('legacy_migration_otp_challenges')==1
        when:def exact=browser.consume(cookie,consume.csrf(),consume.commandId(),wrong)
        then:exact==retry
        form(exact,B.Action.EMAIL_CONSUME).attemptsRemaining()==4
        when:browser.consume(cookie,consume.csrf(),consume.commandId(),code)
        then:def changed=thrown(B.Failure);changed.kind()==B.Kind.CONFLICT
        count('legacy_migration_otp_receipts')==0
    }
    def 'early resend and GET allocate nothing and exact ISSUE replay returns original bundle'(){
        given:def stage=staged();def issue=stage.forms().first();def page=browser.issue(cookie,issue.csrf(),issue.commandId());def resend=form(page,B.Action.EMAIL_ISSUE)
        int commandsBefore=count('legacy_migration_browser_commands'),claimsBefore=count('legacy_migration_browser_claims')
        when:browser.issue(cookie,resend.csrf(),resend.commandId())
        then:def early=thrown(B.Failure);early.kind()==B.Kind.RATE_LIMITED
        count('legacy_migration_browser_claims')==claimsBefore
        when:def status=browser.status(cookie);def replay=browser.issue(cookie,issue.csrf(),issue.commandId())
        then:status==page;replay==page
        count('legacy_migration_browser_commands')==commandsBefore
        count('legacy_migration_otp_outbox')==1
    }
    def 'lost browser result reconciles original domain ISSUE without resend or new challenge'(){
        given:def stage=staged();def issue=stage.forms().first()
        owned{request->def c=commands.inspect(request,cookie,issue.csrf(),issue.commandId(),[C.Purpose.EMAIL_ISSUE] as Set);assert commands.claim(request,cookie,issue.csrf(),c,null,null);fixture.otp.issue(request,migration,cookie,issue.commandId(),O.Purpose.EMAIL)}
        when:def pending=browser.status(cookie)
        then:pending instanceof B.ReadOnlyStatus;pending.status()==B.Status.PENDING_RESULT
        when:def recovered=browser.issue(cookie,issue.csrf(),issue.commandId())
        then:recovered instanceof B.ActionBundle
        count('legacy_migration_otp_challenges')==1
        count('legacy_migration_otp_outbox')==1
        count('legacy_migration_browser_results')==2
        when:def again=browser.issue(cookie,issue.csrf(),issue.commandId())
        then:again==recovered
    }
    def 'wrong browser csrf or endpoint cannot claim a purpose or cause provider work'(){
        given:def page=staged();def issue=page.forms().first();int calls=fixture.fixture.observerCalls;int before=count('legacy_migration_browser_claims')
        when:browser.issue('x'*43,issue.csrf(),issue.commandId())
        then:thrown(B.Failure)
        when:browser.issue(cookie,'x'*43,issue.commandId())
        then:thrown(B.Failure)
        when:browser.consume(cookie,issue.csrf(),issue.commandId(),'123456')
        then:thrown(B.Failure)
        fixture.fixture.observerCalls==calls
        count('legacy_migration_browser_claims')==before
        count('legacy_migration_otp_outbox')==0
    }
    def 'permanent capacity is reserved before continuation stage and immutable history cannot be recycled'(){
        given:fixture.fixture.database.transaction{sql->sql.execute('update legacy_migration_browser_capacity set reserved_slots=100000 where singleton=true')}
        when:begin()
        then:def full=thrown(B.Failure);full.kind()==B.Kind.RATE_LIMITED
        count('legacy_migration_browser_continuations')==0
        count('legacy_migration_browser_commands')==0
        when:fixture.fixture.database.transaction{sql->sql.execute('update legacy_migration_browser_capacity set reserved_slots=0 where singleton=true')}
        then:thrown(org.jooq.exception.DataAccessException)
    }
    def 'original challenge key commitment is private deterministic data and cannot become receipt authority'(){
        given:def page=issued();def consume=form(page,B.Action.EMAIL_CONSUME);String code=delivered()
        def selected=owned{request->commands.inspect(request,cookie,consume.csrf(),consume.commandId(),[C.Purpose.EMAIL_CONSUME] as Set)}
        when:
        def commitment=owned{request->fixture.otp.browserCodeCommitment(request,migration,cookie,selected.form().challengeId(),O.Purpose.EMAIL,code)}
        def expected=HexFormat.of().formatHex(fixture.otpCrypto.hash(commitment.otpKeyId(),'armoney-legacy-migration-otp-code-commitment-v1',code))
        then:commitment.commitmentLowerHex()==expected
        !commitment.toString().contains(code)
        count('legacy_migration_otp_receipts')==0
        when:owned{request->fixture.otp.browserCodeCommitment(request,migration,cookie,selected.form().challengeId(),O.Purpose.PHONE,code)}
        then:thrown(O.Failure)
    }
    def 'same command execution is fenced while original backend owns its permit and recovers after release'(){
        given:def stage=staged();def issue=stage.forms().first();def executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        when:
        owned{request->
            def command=commands.inspect(request,cookie,issue.csrf(),issue.commandId(),[C.Purpose.EMAIL_ISSUE] as Set)
            assert commands.claim(request,cookie,issue.csrf(),command,null,null)
            commands.withExecution(request,cookie,command,{
                def concurrent=executor.submit({
                    try{browser.issue(cookie,issue.csrf(),issue.commandId());null}catch(B.Failure rejected){rejected.kind()}
                } as java.util.concurrent.Callable)
                assert concurrent.get(3,java.util.concurrent.TimeUnit.SECONDS)==B.Kind.UNAVAILABLE
                assert count('legacy_migration_otp_challenges')==0
                null
            } as java.util.function.Supplier)
        }
        def page=browser.issue(cookie,issue.csrf(),issue.commandId())
        then:page instanceof B.ActionBundle
        count('legacy_migration_otp_challenges')==1
        count('legacy_migration_browser_executions')==2 // STAGE + ISSUE; no append-per-retry growth.
        cleanup:executor?.shutdownNow();executor?.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS)
    }
    def 'immutable history cannot be deleted to create fresh command slots'(){
        given:def page=staged();int before=count('legacy_migration_browser_commands')
        when:fixture.fixture.database.transaction{sql->sql.execute('delete from legacy_migration_browser_commands where command_id=?',page.forms().first().commandId())}
        then:thrown(org.jooq.exception.DataAccessException)
        count('legacy_migration_browser_commands')==before
        fixture.fixture.database.transaction{sql->sql.fetchOne('select reserved_slots from legacy_migration_browser_capacity').get(0,Integer)}==32
    }
    def 'interrupted predecessor cannot self block a new borrow of the same physical backend and slot'(){
        given:def page=staged();def issue=page.forms().first();def old=[:]
        when:
        try{
            owned{request->
                old.request=request.requestId()
                def command=commands.inspect(request,cookie,issue.csrf(),issue.commandId(),[C.Purpose.EMAIL_ISSUE] as Set)
                assert commands.claim(request,cookie,issue.csrf(),command,null,null)
                commands.withExecution(request,cookie,command,{
                    old.backend=request.transaction{sql->sql.fetchOne('select backend_pid,backend_start,permit_slot from legacy_migration_browser_executions where command_id=?',issue.commandId()).intoMap()}
                    try{commands.withExecution(request,cookie,command,{throw new AssertionError('same scope reentry accepted')} as java.util.function.Supplier);throw new AssertionError('same scope reentry accepted')}catch(B.Failure rejected){assert rejected.kind()==B.Kind.UNAVAILABLE}
                    Thread.currentThread().interrupt();null
                } as java.util.function.Supplier)
            }
        }finally{Thread.interrupted()}
        then:thrown(com.arman.bank.authservice.domain.LoginProof.Failure)
        fixture.fixture.database.transaction{sql->sql.fetchOne('select active from legacy_migration_browser_executions where command_id=?',issue.commandId()).get(0,Boolean)}
        when:
        owned{request->
            assert request.requestId()!=old.request
            def actual=request.transaction{sql->sql.fetchOne("select a.pid as backend_pid,a.backend_start,l.objid::integer as permit_slot from pg_stat_activity a join pg_locks l on l.pid=a.pid where a.pid=pg_backend_pid() and l.locktype='advisory' and l.classid=cast(? as oid) and l.objsubid=2 and l.granted",0x41524D4F).intoMap()}
            assert actual==old.backend
            def command=commands.inspect(request,cookie,issue.csrf(),issue.commandId(),[C.Purpose.EMAIL_ISSUE] as Set)
            assert commands.claim(request,cookie,issue.csrf(),command,null,null)
            commands.withExecution(request,cookie,command,{
                fixture.otp.issue(request,migration,cookie,issue.commandId(),O.Purpose.EMAIL)
            } as java.util.function.Supplier)
        }
        def recovered=browser.issue(cookie,issue.csrf(),issue.commandId())
        then:recovered instanceof B.ActionBundle
        count('legacy_migration_otp_challenges')==1
        count('legacy_migration_otp_outbox')==1
        fixture.fixture.database.transaction{sql->!sql.fetchOne('select active from legacy_migration_browser_executions where command_id=?',issue.commandId()).get(0,Boolean)}
    }
    def 'expired original binder reads and completed POST replay expose retained status without forms or work'(){
        given:
        def initial=begin();def stageForm=initial.forms().first()
        def nextPage=browser.stage(cookie,stageForm.csrf(),stageForm.commandId())
        if(pending){def issue=nextPage.forms().first();owned{request->def c=commands.inspect(request,cookie,issue.csrf(),issue.commandId(),[C.Purpose.EMAIL_ISSUE] as Set);assert commands.claim(request,cookie,issue.csrf(),c,null,null)}}
        def before=fixture.fixture.database.transaction{sql->['legacy_migration_browser_commands','legacy_migration_browser_claims','legacy_migration_browser_bundles','legacy_migration_browser_results','legacy_migration_otp_outbox'].collectEntries{table->[table,sql.fetchOne('select count(*) from '+table).get(0,Long)]}}
        int observed=fixture.fixture.observerCalls
        // Preserve the immutable foundation row and wait for its genuine original
        // authorization deadline. No clock/deadline/proof row mutation or bypass.
        long waitMillis=java.time.Duration.between(java.time.Instant.now(),initial.originalExpiresAt()).toMillis()+300L
        assert waitMillis>0L&&waitMillis<=120500L
        while(waitMillis>0L){
            Thread.sleep(Math.min(waitMillis,1000L))
            waitMillis=java.time.Duration.between(java.time.Instant.now(),initial.originalExpiresAt()).toMillis()+300L
        }
        when:def status=browser.status(cookie);def replay=browser.stage(cookie,stageForm.csrf(),stageForm.commandId())
        then:
        status instanceof B.ReadOnlyStatus
        replay instanceof B.ReadOnlyStatus
        status.status()==(pending?B.Status.PENDING_RESULT:B.Status.RECOVERY_REQUIRED)
        replay==status
        status.originalExpiresAt()==initial.originalExpiresAt()
        fixture.fixture.observerCalls==observed
        fixture.fixture.database.transaction{sql->before.every{table,number->sql.fetchOne('select count(*) from '+table).get(0,Long)==number}}
        where:pending << [false,true]
    }
}
