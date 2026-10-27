package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.MigrationUserCurrentPort
import com.arman.bank.authservice.application.LegacyMigrationBrowserService as B
import com.arman.bank.authservice.domain.LegacyMigrationFinalization as F
import com.sun.net.httpserver.HttpServer
import spock.lang.Specification
import java.net.http.*
import java.time.*
import java.nio.file.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Real Auth/PostgreSQL and actual UserMain. Provider facts and OTP delivery remain synthetic.
 * The forwarding peer can withhold an actual User acknowledgement; it cannot create a release. */
class MigrationUserReleaseRecoveryIntegrationSpec extends Specification {
    private enum BrowserCheckpoint { PREPARE,BEGIN,STAGE,EMAIL_ISSUE,EMAIL_CONSUME,PHONE_ISSUE,PHONE_CONSUME,DECISION,PUBLICATION,FINAL_PREPARE,FIRST_FINAL,PENDING_STATUS,RESTARTED_FINAL,FINAL_REPLAY,COMPLETED_STATUS }
    def browserStep(BrowserCheckpoint checkpoint,boolean diagnostics,Closure action){
        try{return action.call()}
        catch(B.Failure failure){
            if(diagnostics)java.util.logging.Logger.getLogger(MigrationUserReleaseRecoveryIntegrationSpec.name).warning('armoney_test_browser_failure stage='+checkpoint.name()+' kind='+failure.kind().name())
            throw failure
        }
    }
    MigrationSourcePromotionIntegrationSpec scenario
    HttpServer proxy
    HttpClient forwarding
    AtomicInteger calls=new AtomicInteger()
    def bodies=new CopyOnWriteArrayList<byte[]>()
    volatile String responseMode='ok'
    List<Process> mainChildren=[]
    List<Thread> mainDrainers=[]
    Path mainHosts
    HttpClient mainHttp
    def setup(){scenario=new MigrationSourcePromotionIntegrationSpec();scenario.setup()}
    def cleanup(){
        Throwable failure=null
        for(Closure action:[{closeMains()},{proxy?.stop(0)},{forwarding?.close()},{scenario?.cleanup()}])try{action()}catch(Throwable problem){if(failure==null)failure=problem;else failure.addSuppressed(problem)}
        if(failure!=null)throw failure
    }
    void closeMains(){
        Throwable failure=null
        for(Process child:mainChildren)try{child.destroy();if(!child.waitFor(8,TimeUnit.SECONDS)){child.destroyForcibly();assert child.waitFor(5,TimeUnit.SECONDS)}}catch(Throwable problem){if(failure==null)failure=problem;else failure.addSuppressed(problem)}
        for(Thread drainer:mainDrainers)try{drainer.join(3000);assert !drainer.alive}catch(Throwable problem){if(failure==null)failure=problem;else failure.addSuppressed(problem)}
        try{mainHttp?.close()}finally{if(mainHosts!=null)Files.deleteIfExists(mainHosts)}
        if(failure!=null)throw failure
    }
    Process launchMain(String module,def postgres,int port,Map selected){
        LiveMigrationMainFixture.requireIsolation()
        Path root=Path.of('').toAbsolutePath();if(!Files.isDirectory(root.resolve('auth-service')))root=root.parent
        Path lib=root.resolve(module+'/build/install/'+module+'/lib');assert Files.isDirectory(lib):'Actual Main installDist required'
        String java=Path.of(System.getProperty('java.home'),'bin',System.getProperty('os.name').toLowerCase().contains('win')?'java.exe':'java').toString()
        def builder=new ProcessBuilder(java,'-Xms32m','-Xmx256m','-Djdk.net.hosts.file='+mainHosts.toAbsolutePath(),'-cp',lib.toString()+File.separator+'*','com.arman.bank.'+(module=='auth-service'?'authservice':'userservice')+'.Main')
        def env=builder.environment();def essential=[:];env.each{k,v->if(k.toUpperCase(Locale.ROOT) in ['SYSTEMROOT','WINDIR','TEMP','TMP','PATH'])essential[k]=v}
        env.clear();env.putAll(essential);env.putAll([DB_URL:postgres.jdbcUrl,DB_USER:postgres.username,DB_PASSWORD:postgres.password,PORT:port.toString()])
        selected.each{k,v->assert v!=null;env.put(k.toString(),v.toString())}
        def child=builder.redirectErrorStream(true).start();mainChildren.add(child)
        def drainer=new Thread({->try{child.inputStream.withCloseable{stream->byte[] bytes=new byte[4096];while(stream.read(bytes)!=-1)Arrays.fill(bytes,(byte)0)}}catch(IOException ignored){}} as Runnable,'release-main-private-drain')
        drainer.daemon=true;mainDrainers.add(drainer);drainer.start();child
    }
    void readyMain(Process child,int port){
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);boolean ready=false
        while(child.alive&&!ready&&System.nanoTime()<limit){
            try{ready=mainHttp.send(HttpRequest.newBuilder(URI.create('http://127.0.0.1:'+port+'/health/ready')).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200}catch(IOException ignored){}
            if(!ready)Thread.sleep(50)
        }
        assert ready:'Owned Main readiness failed; private process output discarded'
    }
    def prepare(boolean sso=false){
        def method=MigrationSourcePromotionIntegrationSpec.getDeclaredMethod('prepared',Boolean.TYPE,Boolean.TYPE)
        method.accessible=true
        method.invoke(scenario,sso,false)
    }
    def managed(){scenario.fixture.fixture}
    def producer(){scenario.producer}
    def owned(Closure work){def f=managed();new PostgresLoginRequestScope(f.database).inRequest(f.ACTOR){request->work.call(request)}}
    def promote(def context){
        owned{request->producer().withFinalDecision(request,context.original.admission.migrationId(),context.original.admission.ownerCapability(),context.command,
            {sql,decision,ack,evidence->scenario.consumer.promote(sql,decision,ack,evidence)} as PostgresMigrationFinalAckStore.FinalWork)}
    }
    def prepareSecondOwner(){
        awaitNaturalAdmissionCredits()
        def f=managed();String email='second-historical@example.test',phone='+971521234567'
        f.auth.register(email,f.PASSWORD,phone)
        def token=f.auth.login(email,f.PASSWORD).accessToken();def owner=f.auth.me('Bearer '+token).id()
        def admission=f.auth.beginLegacyMigration(email,f.PASSWORD,UUID.randomUUID(),f.local)
        scenario.fixture.user.bootstrap(owner,phone)
        f.contacts.put('signed-second',[email:email,phone:phone]);f.signedSubject='signed-second'
        def target=f.managed.start(admission.migrationId(),admission.ownerCapability(),UUID.randomUUID())
        f.interactive(target,'legacy_migration_managed_attempts');f.managed.complete(f.callback(target))
        def original=[identityId:owner,token:token,admission:admission]
        scenario.fixture.service.stage(admission.migrationId(),admission.ownerCapability())
        scenario.fixture.consumed(original,com.arman.bank.authservice.domain.LegacyMigrationOtp.Purpose.EMAIL)
        scenario.fixture.consumed(original,com.arman.bank.authservice.domain.LegacyMigrationOtp.Purpose.PHONE)
        def decision=scenario.fixture.authorize(original)
        producer().publish(admission.migrationId(),admission.ownerCapability())
        def command=owned{request->producer().reserveFinalCommand(request,admission.migrationId(),admission.ownerCapability())}
        [original:original,decision:decision,command:command]
    }
    void awaitNaturalAdmissionCredits(){
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12)
        byte[] writer=com.arman.bank.authservice.domain.LoginProof.writerHash(managed().ACTOR)
        boolean full=false
        while(!full&&System.nanoTime()<until){
            full=managed().database.transaction{sql->
                def row=sql.fetchOne('select least(20,credits+greatest(0,extract(epoch from clock_timestamp()-refilled_at))*2)>=20 as full from sso_login_proof_admission where writer_hash=?',new Object[]{writer})
                row==null||Boolean.TRUE.equals(row.get('full',Boolean))
            }
            if(!full)Thread.sleep(50)
        }
        assert full:'Genuine fixture admission credits did not naturally replenish within 12 seconds'
    }
    def resolve(def context){owned{request->producer().resolveUser(request,context.original.admission.migrationId(),context.original.admission.ownerCapability())}}
    def count(String table){managed().count(table)}
    def current(def context){
        def decision=managed().database.transaction{it.fetchOne('select decision_id from legacy_migration_final_completions where migration_id=?',context.original.admission.migrationId()).get(0,UUID)}
        scenario.currentUser.observe(new MigrationUserCurrentPort.Query(UUID.randomUUID(),context.original.identityId,context.original.admission.migrationId(),decision,1,1,1),Duration.ofSeconds(4))
    }
    def browser(){
        def f=managed()
        def services=new PostgresLegacyMigrationBrowserService.ContinuationServices(scenario.fixture.otp,scenario.fixture.user.stagePort(),scenario.fixture.journal,producer(),scenario.consumer,f.custody)
        new PostgresLegacyMigrationBrowserService(f.database,f.auth,f.local,f.account,f.managed,f.crypto,
            URI.create(f.account.settings().issuer()+'/protocol/openid-connect/auth'),URI.create(f.managed.settings().issuer()+'/protocol/openid-connect/auth'),true,services)
    }
    def browserPrepared(boolean diagnostics=false){
        def original=scenario.fixture.admitted(false);def f=managed();def settings=scenario.fixture.user.syntheticPublicationSettings()
        int port=scenario.fixture.unavailableAuth.address.port;scenario.fixture.unavailableAuth.stop(0);scenario.fixture.unavailableAuth=null
        def routes=new LegacyMigrationFinalizationRoutes(scenario.fixture.journal,settings.decisionKey(),true)
        scenario.authRead=io.javalin.Javalin.create{config->routes.configure(config)}.start(port)
        scenario.consumer=new PostgresMigrationBankingEligibilityStore(f.crypto)
        scenario.producer=PostgresMigrationFinalAckStore.synthetic(f.database,f.custody,f.crypto,scenario.fixture.journal,scenario.fixture.otp,f.managed,scenario.consumer,f.ACTOR,settings.publicationKey(),settings.origin())
        forwardingPeer()
        String cookie=original.admission.ownerCapability();UUID migration=original.admission.migrationId()
        def commands=new PostgresLegacyMigrationBrowserCommandStore(f.crypto,f.custody,f.ACTOR)
        def service=browser();def page=browserStep(BrowserCheckpoint.BEGIN,diagnostics){owned{request->commands.begin(request,migration,cookie)}};def form=page.forms().first()
        page=browserStep(BrowserCheckpoint.STAGE,diagnostics){service.stage(cookie,form.csrf(),form.commandId())}
        [B.Action.EMAIL_ISSUE,B.Action.PHONE_ISSUE].each{action->
            form=page.forms().find{it.action()==action};page=browserStep(action==B.Action.EMAIL_ISSUE?BrowserCheckpoint.EMAIL_ISSUE:BrowserCheckpoint.PHONE_ISSUE,diagnostics){service.issue(cookie,form.csrf(),form.commandId())}
            def lease=scenario.fixture.otp.claimDelivery().orElseThrow();def payload=scenario.fixture.otpCrypto.decrypt(lease.envelope(),lease.encrypted())
            assert scenario.fixture.otp.finishDelivery(lease.deliveryId(),lease.fence(),true)
            form=page.forms().find{it.action() in [B.Action.EMAIL_CONSUME,B.Action.PHONE_CONSUME]}
            page=browserStep(action==B.Action.EMAIL_ISSUE?BrowserCheckpoint.EMAIL_CONSUME:BrowserCheckpoint.PHONE_CONSUME,diagnostics){service.consume(cookie,form.csrf(),form.commandId(),payload.code())}
        }
        form=page.forms().first();page=browserStep(BrowserCheckpoint.DECISION,diagnostics){service.authorizePublication(cookie,form.csrf(),form.commandId())}
        form=page.forms().first();page=browserStep(BrowserCheckpoint.PUBLICATION,diagnostics){service.publish(cookie,form.csrf(),form.commandId())}
        form=page.forms().first();page=browserStep(BrowserCheckpoint.FINAL_PREPARE,diagnostics){service.prepareFinal(cookie,form.csrf(),form.commandId())}
        [original:original,browser:service,form:page.forms().first(),cookie:cookie]
    }
    void replaceProducer(URI origin){
        scenario.producer.close()
        def f=managed();def settings=scenario.fixture.user.syntheticPublicationSettings()
        scenario.producer=PostgresMigrationFinalAckStore.synthetic(f.database,f.custody,f.crypto,scenario.fixture.journal,scenario.fixture.otp,f.managed,scenario.consumer,f.ACTOR,settings.publicationKey(),origin)
    }
    void forwardingPeer(){
        def settings=scenario.fixture.user.syntheticPublicationSettings()
        forwarding=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()
        proxy=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        proxy.createContext('/v1/internal/migrations/legacy/'){exchange->
            boolean release=exchange.requestURI.path=='/v1/internal/migrations/legacy/user-resolve'
            byte[] body=exchange.requestBody.readNBytes(12289)
            if(release){calls.incrementAndGet();bodies.add(body)}
            if(release&&responseMode in ['unavailable','limited']){exchange.sendResponseHeaders(responseMode=='limited'?429:503,-1);exchange.close();return}
            def request=HttpRequest.newBuilder(settings.origin().resolve(exchange.requestURI.path)).timeout(Duration.ofSeconds(3))
                .header('Content-Type','application/json').header('X-ArMoney-Migration-Publication-Key',settings.publicationKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
            def reply=forwarding.send(request,HttpResponse.BodyHandlers.ofByteArray())
            if(release&&responseMode=='lost'){exchange.sendResponseHeaders(503,-1);exchange.close();return}
            byte[] bytes=release&&responseMode=='unknown'?'{}'.bytes:reply.body()
            if(release&&responseMode in ['wrong-migration','wrong-decision','aborted','extra','typed']){
                def json=new com.fasterxml.jackson.databind.ObjectMapper();Map value=json.readValue(reply.body(),Map)
                if(responseMode=='wrong-migration')value.migration_id=UUID.randomUUID().toString()
                if(responseMode=='wrong-decision')value.decision_id=UUID.randomUUID().toString()
                if(responseMode=='aborted')value.state='ABORTED'
                if(responseMode=='extra')value.extra=true
                if(responseMode=='typed')value.migration_id=1L
                bytes=json.writeValueAsBytes(value)
            }
            exchange.responseHeaders.set('Content-Type','application/json');exchange.sendResponseHeaders(reply.statusCode(),bytes.length)
            exchange.responseBody.write(bytes);exchange.close()
        }
        proxy.start();replaceProducer(URI.create('http://127.0.0.1:'+proxy.address.port))
    }
    def 'release rejects wrong identifiers aborted extra and typed responses without an Auth acknowledgement'(){
        given:def context=prepare();promote(context);forwardingPeer();responseMode=fault
        when:resolve(context)
        then:thrown(F.Failure);calls.get()==1;count('legacy_migration_user_release_acks')==0;count('legacy_migration_final_completions')==1
        where:fault << ['wrong-migration','wrong-decision','aborted','extra','typed']
    }
    def 'different publication writer cannot release a completed original command'(){
        given:def context=prepare();promote(context);forwardingPeer();def f=managed();def settings=scenario.fixture.user.syntheticPublicationSettings()
        def foreign=PostgresMigrationFinalAckStore.synthetic(f.database,f.custody,f.crypto,scenario.fixture.journal,scenario.fixture.otp,f.managed,scenario.consumer,'other-writer',settings.publicationKey(),URI.create('http://127.0.0.1:'+proxy.address.port))
        when:owned{request->foreign.resolveUser(request,context.original.admission.migrationId(),context.original.admission.ownerCapability())}
        then:thrown(F.Failure);calls.get()==0;count('legacy_migration_user_release_acks')==0
        cleanup:foreign?.close()
    }
    def 'expired cached completed readback fails closed when terminal evidence is missing or corrupt'(){
        given:def context=browserPrepared();def completed=context.browser.finish(context.cookie,context.form.csrf(),context.form.commandId())
        assert completed.status()==B.Status.COMPLETED
        // Negative tampering fixture only. Restore all published guards before readback;
        // shortening this corrupted browser row is not evidence of genuine clock expiry.
        managed().database.transaction{sql->
            sql.execute('alter table legacy_migration_browser_continuations disable trigger migration_browser_continuations_immutable')
            sql.execute("update legacy_migration_browser_continuations set account_deadline=clock_timestamp()-interval '1 second'")
            sql.execute('alter table legacy_migration_browser_continuations enable trigger migration_browser_continuations_immutable')
            sql.execute('alter table legacy_migration_final_completions disable trigger legacy_migration_final_completions_immutable')
            if(missing){
                sql.execute('alter table legacy_migration_user_release_acks disable trigger migration_user_release_ack_immutable')
                sql.execute('delete from legacy_migration_user_release_acks')
                sql.execute('alter table legacy_migration_user_release_acks enable trigger migration_user_release_ack_immutable')
                sql.execute('delete from legacy_migration_final_completions')
            }else sql.execute('update legacy_migration_final_completions set provenance_hash=?','0'*64)
            sql.execute('alter table legacy_migration_final_completions enable trigger legacy_migration_final_completions_immutable')
        }
        when:context.browser.status(context.cookie)
        then:def failure=thrown(B.Failure);failure.kind()==B.Kind.UNAVAILABLE;calls.get()==1
        where:missing << [true,false]
    }
    def 'browser final retry and read-only status remain pending until verified durable release'(){
        given:def context=browserStep(BrowserCheckpoint.PREPARE,true){browserPrepared(true)};responseMode='lost'
        when:browserStep(BrowserCheckpoint.FIRST_FINAL,true){context.browser.finish(context.cookie,context.form.csrf(),context.form.commandId())}
        then:def failure=thrown(B.Failure);failure.kind()==B.Kind.UNAVAILABLE;!failure.clearCookies()
        count('legacy_migration_final_completions')==1;count('legacy_migration_user_release_acks')==0
        when:def pending=browserStep(BrowserCheckpoint.PENDING_STATUS,true){context.browser.status(context.cookie)}
        then:pending.status()==B.Status.PENDING_RESULT;calls.get()==1
        when:
        responseMode='ok';replaceProducer(URI.create('http://127.0.0.1:'+proxy.address.port))
        def restarted=browser();def completed=browserStep(BrowserCheckpoint.RESTARTED_FINAL,true){restarted.finish(context.cookie,context.form.csrf(),context.form.commandId())}
        then:completed.status()==B.Status.COMPLETED;calls.get()==2;Arrays.equals(bodies[0],bodies[1])
        when:def replay=browserStep(BrowserCheckpoint.FINAL_REPLAY,true){restarted.finish(context.cookie,context.form.csrf(),context.form.commandId())};def status=browserStep(BrowserCheckpoint.COMPLETED_STATUS,true){restarted.status(context.cookie)}
        then:replay==completed;status==completed;calls.get()==2;count('legacy_migration_final_completions')==1
    }
    def 'worker acknowledgement completes read-only browser status without a cached FINAL result or another POST'(){
        given:def context=browserPrepared();responseMode='lost'
        try{context.browser.finish(context.cookie,context.form.csrf(),context.form.commandId());throw new AssertionError('lost acknowledgement reported completed')}
        catch(B.Failure rejected){assert rejected.kind()==B.Kind.UNAVAILABLE}
        def resultCount={managed().database.transaction{it.fetchOne('select count(*) from legacy_migration_browser_results where command_id=?',context.form.commandId()).get(0,Long)}}
        assert resultCount()==0L
        when:
        responseMode='ok';replaceProducer(URI.create('http://127.0.0.1:'+proxy.address.port));producer().recoverNextUserRelease()
        long bundles=count('legacy_migration_browser_bundles')
        def status=browser().status(context.cookie)
        then:
        status instanceof B.ReadOnlyStatus
        status.status()==B.Status.COMPLETED
        resultCount()==0L
        count('legacy_migration_browser_bundles')==bundles
        calls.get()==2
        count('legacy_migration_final_completions')==1
    }
    def 'lost or unknown release acknowledgement recovers original command after store restart for either mode'(){
        given:def context=prepare(sso);promote(context);forwardingPeer();responseMode=fault=='ack-write'?'ok':fault
        if(fault=='ack-write')managed().database.transaction{sql->
            sql.execute("create function qa_release_ack_failure() returns trigger language plpgsql as 'begin raise exception ''synthetic acknowledgement rollback''; end;'")
            sql.execute('create trigger qa_release_ack_failure before insert on legacy_migration_user_release_acks for each row execute function qa_release_ack_failure()')
        }
        when:resolve(context)
        then:
        def failure=thrown(RuntimeException)
        if(fault=='ack-write')assert failure instanceof org.jooq.exception.DataAccessException
        else assert failure instanceof F.Failure
        count('legacy_migration_user_release_acks')==0;count('legacy_migration_final_completions')==1
        when:
        if(fault=='ack-write')managed().database.transaction{it.execute('drop trigger qa_release_ack_failure on legacy_migration_user_release_acks')}
        responseMode='ok';replaceProducer(URI.create('http://127.0.0.1:'+proxy.address.port))
        producer().recoverNextUserRelease()
        def observed=current(context)
        then:
        observed.query().identityId()==context.original.identityId
        calls.get()==2
        Arrays.equals(bodies[0],bodies[1])
        count('legacy_migration_user_release_acks')==1
        count('migration_banking_authorities')==1
        count('migration_banking_grants')==1
        count('legacy_migration_final_completions')==1
        when:resolve(context);producer().recoverNextUserRelease()
        then:noExceptionThrown();calls.get()==2;count('legacy_migration_user_release_acks')==1
        where:
        sso | fault
        false | 'lost'
        true | 'lost'
        false | 'unknown'
        true | 'unknown'
        false | 'ack-write'
    }
    def 'User outage stays unreleased and shared release gate honors rate limit without extra requests'(){
        given:def context=prepare();promote(context);forwardingPeer();responseMode='limited'
        when:resolve(context)
        then:def limited=thrown(F.Failure);limited.error()==F.Error.RATE_LIMITED;calls.get()==1
        when:producer().recoverNextUserRelease()
        then:thrown(F.Failure);calls.get()==1;count('legacy_migration_user_release_acks')==0
        when:current(context)
        then:thrown(IllegalStateException)
    }
    def 'concurrent original command resolution creates one immutable acknowledgement and no second FINAL'(){
        given:def context=prepare();promote(context);forwardingPeer();def start=new CountDownLatch(1);def pool=Executors.newFixedThreadPool(2)
        when:
        def jobs=(1..2).collect{pool.submit({start.await(3,TimeUnit.SECONDS);try{resolve(context);true}catch(F.Failure unavailable){assert unavailable.error()==F.Error.RATE_LIMITED;false}} as Callable<Boolean>)}
        start.countDown();def outcomes=jobs.collect{it.get(8,TimeUnit.SECONDS)}
        then:outcomes.any{it};calls.get()==1;count('legacy_migration_user_release_acks')==1;count('legacy_migration_final_completions')==1
        when:managed().database.transaction{it.execute("update legacy_migration_user_release_acks set provenance_hash=?",'0'*64)}
        then:thrown(org.jooq.exception.DataAccessException)
        when:managed().database.transaction{it.execute('delete from legacy_migration_user_release_acks')}
        then:thrown(org.jooq.exception.DataAccessException)
        cleanup:pool?.shutdownNow();pool?.awaitTermination(3,TimeUnit.SECONDS)
    }
    def 'independent store instances reconcile the same completion without duplicate immutable acknowledgement'(){
        given:def context=prepare();promote(context);forwardingPeer();def f=managed();def settings=scenario.fixture.user.syntheticPublicationSettings()
        def peer=PostgresMigrationFinalAckStore.synthetic(f.database,f.custody,f.crypto,scenario.fixture.journal,scenario.fixture.otp,f.managed,scenario.consumer,f.ACTOR,settings.publicationKey(),URI.create('http://127.0.0.1:'+proxy.address.port))
        def start=new CountDownLatch(1);def pool=Executors.newFixedThreadPool(2)
        when:
        def jobs=[producer(),peer].collect{store->pool.submit({start.await(3,TimeUnit.SECONDS);owned{request->store.resolveUser(request,context.original.admission.migrationId(),context.original.admission.ownerCapability())};true} as Callable<Boolean>)}
        start.countDown();def results=jobs.collect{it.get(8,TimeUnit.SECONDS)}
        then:results.every{it};calls.get() in 1..2;count('legacy_migration_user_release_acks')==1;count('legacy_migration_final_completions')==1
        if(calls.get()==2)assert Arrays.equals(bodies[0],bodies[1])
        cleanup:pool?.shutdownNow();pool?.awaitTermination(3,TimeUnit.SECONDS);peer?.close()
    }
    def 'fair database cursor passes a corrupted genuine completion and releases a different genuine owner'(){
        given:
        def first=prepare();promote(first)
        def second=prepareSecondOwner();promote(second)
        forwardingPeer()
        def order=managed().database.transaction{it.fetch('select migration_id from legacy_migration_final_completions order by decision_id').getValues('migration_id',UUID)}
        def poisoned=order.first();def healthy=first.original.admission.migrationId()==poisoned?second:first
        // Both graphs were created by genuine use cases before this isolated corruption probe.
        managed().database.transaction{sql->
            sql.execute('alter table legacy_migration_publication_decisions disable trigger legacy_migration_publication_decision_immutable')
            sql.execute('update legacy_migration_publication_decisions set journal_hash=? where migration_id=?','0'*64,poisoned)
            sql.execute('alter table legacy_migration_publication_decisions enable trigger legacy_migration_publication_decision_immutable')
        }
        awaitNaturalAdmissionCredits()
        when:producer().recoverNextUserRelease()
        then:thrown(RuntimeException);calls.get()==0;count('legacy_migration_user_release_acks')==0
        when:producer().recoverNextUserRelease();def observation=current(healthy)
        then:observation.query().identityId()==healthy.original.identityId;calls.get()==1;count('legacy_migration_user_release_acks')==1
        managed().database.transaction{it.fetchOne('select migration_id from legacy_migration_user_release_acks').get(0,UUID)}==healthy.original.admission.migrationId()
        count('legacy_migration_final_completions')==2
    }
    def 'corrupted retained acknowledgement fails closed without another User call'(){
        given:def context=prepare();promote(context);forwardingPeer();resolve(context)
        // Isolated tampering probe only: restore the immutable guard before exercising readback.
        managed().database.transaction{sql->
            sql.execute('alter table legacy_migration_user_release_acks disable trigger migration_user_release_ack_immutable')
            sql.execute('update legacy_migration_user_release_acks set provenance_hash=?','0'*64)
            sql.execute('alter table legacy_migration_user_release_acks enable trigger migration_user_release_ack_immutable')
        }
        when:resolve(context)
        then:thrown(F.Failure);calls.get()==1;count('legacy_migration_user_release_acks')==1
    }
    def 'rolled back FINAL cannot release User reservation or revoke old session'(){
        given:def context=prepare();forwardingPeer()
        when:
        owned{request->producer().withFinalDecision(request,context.original.admission.migrationId(),context.original.admission.ownerCapability(),context.command,
            {sql,decision,ack,evidence->scenario.consumer.promote(sql,decision,ack,evidence);throw new IllegalStateException('synthetic rollback')} as PostgresMigrationFinalAckStore.FinalWork)}
        then:thrown(IllegalStateException)
        when:producer().recoverNextUserRelease()
        then:noExceptionThrown();calls.get()==0;count('legacy_migration_final_completions')==0;count('legacy_migration_user_release_acks')==0
        managed().auth.me('Bearer '+context.original.token).id()==context.original.identityId
        when:resolve(context)
        then:thrown(F.Failure);calls.get()==0
    }
    def 'worker recovers durable completion after real original account deadline expires'(){
        given:def context=browserPrepared();responseMode='unavailable'
        try{context.browser.finish(context.cookie,context.form.csrf(),context.form.commandId());throw new AssertionError('outage reported completed')}
        catch(B.Failure rejected){assert rejected.kind()==B.Kind.UNAVAILABLE}
        Instant deadline=managed().database.transaction{it.fetchOne('select original_account_deadline from legacy_migration_publication_decisions where migration_id=?',context.original.admission.migrationId()).get(0,OffsetDateTime).toInstant()}
        long waitMillis=Duration.between(Instant.now(),deadline).toMillis()+300L
        assert waitMillis>0&&waitMillis<=120500L
        while(waitMillis>0){Thread.sleep(Math.min(waitMillis,1000L));waitMillis=Duration.between(Instant.now(),deadline).toMillis()+300L}
        assert context.browser.status(context.cookie).status()==B.Status.PENDING_RESULT
        when:
        responseMode='ok';replaceProducer(URI.create('http://127.0.0.1:'+proxy.address.port))
        def worker=new MigrationUserReleaseWorker(producer());worker.tick()
        def observed=current(context)
        then:observed.query().identityId()==context.original.identityId;calls.get()==2;count('legacy_migration_user_release_acks')==1
        browser().status(context.cookie).status()==B.Status.COMPLETED
        cleanup:worker?.close()
    }
    def 'actual AuthMain restart resumes durable release after actual UserMain outage on retained databases'(){
        given:
        LiveMigrationMainFixture.requireIsolation()
        def context=prepare();promote(context);def f=managed()
        def publication=scenario.fixture.user.syntheticPublicationSettings();def currentSettings=scenario.fixture.user.syntheticCurrentSettings()
        int authPort=scenario.authRead.port()
        scenario.fixture.user.close();scenario.authRead.stop();scenario.authRead=null
        new ServerSocket(8080,0,InetAddress.getByName('0.0.0.0')).close() // Isolated runner only; never replace a listener.
        mainHosts=Files.createTempFile('release-owned-main-hosts-','.txt');Files.writeString(mainHosts,'127.0.0.1 localhost user-service\n::1 localhost\n')
        mainHttp=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()
        def env=LegacyMigrationConfigurationSpec.environment()
        env.putAll([AUTH_MIGRATION_ACTOR_ID:f.ACTOR,SSO_ISSUER:f.ISSUER,AUTH_MIGRATION_LEGACY_CLIENT_ID:'legacy-client',AUTH_MIGRATION_MANAGED_CLIENT_ID:'managed-client',MIGRATION_PUBLIC_ORIGIN:'https://migration.example',
            AUTH_MIGRATION_CUSTODY_CURRENT_KEY_ID:'custody',AUTH_MIGRATION_DIGEST_CURRENT_KEY_ID:'digest-v1',AUTH_MIGRATION_PROOF_CURRENT_KEY_ID:'proof',AUTH_MIGRATION_OTP_CURRENT_KEY_ID:'otp',
            AUTH_TO_USER_MIGRATION_PUBLICATION_KEY:publication.publicationKey(),USER_TO_AUTH_MIGRATION_DECISION_KEY:publication.decisionKey(),
            AUTH_TO_USER_MIGRATION_STAGE_KEY:'synthetic-release-main-stage-key-unique',SSO_CLIENT_ID:'legacy-client',
            SSO_CLIENT_SECRET:'synthetic-release-main-introspection-unique',SSO_INTROSPECTION_URL:'http://127.0.0.1:1/sso/realms/armoney/protocol/openid-connect/token/introspect'])
        ['CUSTODY_ENCRYPTION':['custody',71],'DIGEST':['digest-v1',72],'PROOF':['proof',73],'OTP_HMAC':['otp',83],'OTP_ENCRYPTION':['otp',84]].each{role,value->
            env['AUTH_MIGRATION_'+role+'_KEYS']=groovy.json.JsonOutput.toJson([(value[0]):Base64.encoder.encodeToString(([value[1]]*32) as byte[])])
        }
        ['LEGACY','MANAGED'].each{mode->['AUTHORIZATION_ENDPOINT':'auth','TOKEN_ENDPOINT':'token','JWKS_URI':'certs'].each{suffix,path->env['AUTH_MIGRATION_'+mode+'_'+suffix]=f.ISSUER+'/protocol/openid-connect/'+path}}
        env.ARMONEY_SSO_MANAGED_PAIRS=groovy.json.JsonOutput.toJson([[issuer:f.ISSUER,client_id:'managed-client']])
        env.ARMONEY_SSO_LEGACY_PAIRS=groovy.json.JsonOutput.toJson([[issuer:f.ISSUER,client_id:'legacy-client']])
        def first=launchMain('auth-service',f.postgres,authPort,env);readyMain(first,authPort)
        Thread.sleep(6500) // One genuine scheduled release attempt while UserMain is stopped.
        assert first.alive;assert count('legacy_migration_user_release_acks')==0
        first.destroyForcibly();assert first.waitFor(5,TimeUnit.SECONDS)
        def userEnv=[INTERNAL_AUTH_KEY:env.INTERNAL_AUTH_KEY,USER_MIGRATION_BASELINE_ENABLED:'true',USER_MIGRATION_WRITER_ID:'migration-qa',
            AUTH_TO_USER_MIGRATION_STAGE_KEY:env.AUTH_TO_USER_MIGRATION_STAGE_KEY,AUTH_TO_USER_MIGRATION_PUBLICATION_KEY:publication.publicationKey(),
            USER_TO_AUTH_MIGRATION_DECISION_KEY:publication.decisionKey(),AUTH_MIGRATION_PRIMARY_ORIGIN:'http://127.0.0.1:'+authPort,
            USER_LIVE_REVISION_ENABLED:'true',USER_LIVE_CURRENT_WRITER_ID:currentSettings.currentWriter(),AUTH_TO_USER_MIGRATION_USER_CURRENT_KEY:currentSettings.currentKey(),
            USER_LIVE_MUTATION_WRITER_ID:currentSettings.mutationWriter(),USER_TO_AUTH_MIGRATION_USER_MUTATION_KEY:currentSettings.mutationKey()]
        def user=launchMain('user-service',scenario.fixture.userPg,8080,userEnv);readyMain(user,8080)

        when:
        def restarted=launchMain('auth-service',f.postgres,authPort,env);readyMain(restarted,authPort)
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(25)
        while(count('legacy_migration_user_release_acks')==0&&restarted.alive&&System.nanoTime()<limit)Thread.sleep(100)
        scenario.currentUser.close()
        def selected=new MigrationUserRevisionConfiguration(true,URI.create('http://user-service:8080'),currentSettings.currentWriter(),currentSettings.currentKey(),null,currentSettings.mutationWriter(),currentSettings.mutationKey())
        scenario.currentUser=new HttpMigrationUserCurrent(selected,URI.create('http://127.0.0.1:8080'),HttpCodeSessionObservations.privateClient())
        def observation=current(context)
        then:
        restarted.alive;user.alive
        !first.alive;first.pid()!=restarted.pid()
        count('legacy_migration_user_release_acks')==1
        count('legacy_migration_final_completions')==1
        owned{request->request.transaction{sql->PostgresMigrationFinalAckStore.releaseAcknowledged(sql,context.original.admission.migrationId(),f.crypto,f.ACTOR)}}
        observation.query().identityId()==context.original.identityId
    }
}
