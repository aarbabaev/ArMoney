package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.*
import com.arman.bank.authservice.domain.*
import com.arman.bank.runtime.Database
import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.net.http.*
import java.time.*
import java.util.concurrent.*

/** Real PG + real RSA verification + actual loopback exact12 HTTP adapter.
 * Token/JWKS transport is explicitly synthetic. Not genuine Keycloak/runtime/TLS/financial acceptance.
 * No SQL stage ACK, provider receipt, grant, contact receipt or managed marker is seeded. */
class LegacyMigrationManagedProofIntegrationSpec extends Specification {
    static final String ISSUER='https://issuer.example/sso/realms/armoney'
    static final String PHONE='+971501234567'
    static final String EMAIL='historical@example.test'
    static final String PASSWORD='synthetic long migration password'
    static final String ACTOR='migration-auth'
    PostgreSQLContainer postgres
    Database database
    AuthService auth
    PostgresAuthStore identities
    LegacyMigrationCrypto custody
    LegacyMigrationManagedProofCrypto crypto
    PostgresLegacyMigrationStore local
    PostgresLegacyMigrationManagedProofStore managed
    PostgresLegacyMigrationAccountProofStore account
    LegacyMigrationManagedProviderClient managedAdapter
    LegacyMigrationLegacyAccountProviderClient accountAdapter
    HttpCodeSessionObservations observer
    HttpMigrationProviderReadiness readinessObserver
    boolean providerEmailVerified
    String readinessFault
    HttpServer server
    ExecutorService serverThreads
    def key
    String nonce
    Map<String,String> callbackNonces=new ConcurrentHashMap<>()
    Map<String,String> callbackSubjects=new ConcurrentHashMap<>()
    Map<String,Map> contacts=new ConcurrentHashMap<>()
    List<String> observedSubjects=new CopyOnWriteArrayList<>()
    String signedSubject='signed-existing'
    long tokenLifetime=60
    boolean negative
    int tokenCalls
    int observerCalls
    // Finite technical phases only: no claims, contacts, bodies, URLs or exception text.
    Map<String,java.util.concurrent.atomic.AtomicInteger> diagnosticPhases=new ConcurrentHashMap<>()
    void phase(String name){diagnosticPhases.computeIfAbsent(name,{new java.util.concurrent.atomic.AtomicInteger()}).incrementAndGet()}
    String phaseSummary(){['TOKEN_RESPONSE','JWT_VERIFIED','OBSERVATION','SINK_ACCEPTED','RECEIPT_COMMIT'].collect{it+':'+(diagnosticPhases.get(it)?.get()?:0)}.join(',')}
    def diagnosed(Closure operation){
        try{return operation.call()}catch(RuntimeException failure){failure.addSuppressed(new AssertionError('Migration fixture phases '+phaseSummary()));throw failure}
    }
    def setup(){
        providerEmailVerified=true
        postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        identities=new PostgresAuthStore(database)
        auth=new AuthService(identities,new Argon2Passwords(),Clock.systemUTC(),{id,phone->} as RegistrationProfiles)
        custody=new LegacyMigrationCrypto('custody',[custody:([71]*32) as byte[]],([72]*32) as byte[])
        crypto=new LegacyMigrationManagedProofCrypto('proof',[proof:([73]*32) as byte[]])
        local=new PostgresLegacyMigrationStore(database,custody,true)
        key=new RSAKeyGenerator(2048).keyID('proof-fixture-signing').generate()
        server=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        serverThreads=Executors.newFixedThreadPool(2);server.executor=serverThreads
        server.createContext('/sso/realms/armoney/armoney-status/code-session'){exchange->
            try{
                assert exchange.requestMethod=='POST'
                assert exchange.requestHeaders.getFirst('X-ArMoney-Auth-Status-Key')=='s'*32
                def request=new JsonSlurper().parse(exchange.requestBody)
                boolean v2=exchange.requestURI.path.endsWith('/v2')
                def expectedFields=v2 ? ['protocol_version','purpose','original_redirect_uri','issuer','subject','client_id','user_session_id'] : ['issuer','subject','client_id','user_session_id']
                assert request.keySet()==(expectedFields as Set)
                if(v2){assert request.protocol_version==2;assert request.purpose=='MIGRATION_MANAGED';assert request.original_redirect_uri=='https://migration.example/migration/managed/callback'}
                observerCalls++
                observedSubjects.add(request.subject as String)
                def contact=contacts.get(request.subject as String)
                assert contact!=null
                long observed=Instant.now().epochSecond
                def response=[issuer:request.issuer,subject:request.subject,client_id:request.client_id,user_session_id:request.user_session_id,
                    username:contact.phone,email:contact.email,phone_number:contact.phone,enabled:!negative,user_session_active:true,client_session_active:true,observed_at:Instant.ofEpochSecond(observed).toString(),valid_until:Instant.ofEpochSecond(observed+5).toString()]
                if(v2){
                    response=[protocol_version:2,purpose:request.purpose,original_redirect_uri:request.original_redirect_uri,facts:response,provider_email_verified:providerEmailVerified]
                    if(readinessFault=='MALFORMED')response.provider_email_verified='true'
                }
                byte[] body=JsonOutput.toJson(response).getBytes('UTF-8')
                exchange.responseHeaders.set('Content-Type','application/json');exchange.sendResponseHeaders(v2&&readinessFault=='UNAVAILABLE'?503:200,body.length);exchange.responseBody.write(body)
            }finally{exchange.close()}
        }
        server.start()
        contacts.put('signed-existing',[email:EMAIL,phone:PHONE])
        def scopes=new CodeIntentPairScopes(JsonOutput.toJson([[issuer:ISSUER,client_id:'legacy-client'],[issuer:ISSUER,client_id:'managed-client']]),'[]')
        observer=new HttpCodeSessionObservations(URI.create('http://127.0.0.1:'+server.address.port),'s'*32,scopes,HttpCodeSessionObservations.privateClient())
        readinessObserver=new HttpMigrationProviderReadiness(URI.create('http://127.0.0.1:'+server.address.port),'s'*32,scopes,HttpMigrationProviderReadiness.privateClient())
        def http=new FixtureHttp()
        http.responder={HttpRequest request,handler->
            boolean token=request.uri().path.endsWith('/token')
            byte[] body
            if(token){
                tokenCalls++
                def fields=readForm(request)
                assert fields.grant_type=='authorization_code'
                assert fields.code_verifier!=null
                long current=Instant.now().epochSecond
                def claims=[iss:ISSUER,sub:callbackSubjects.get(fields.code),sid:'signed-session',aud:fields.client_id,azp:fields.client_id,nonce:callbackNonces.get(fields.code),auth_time:current,iat:current,exp:current+tokenLifetime]
                def jws=new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID('proof-fixture-signing').type(JOSEObjectType.JWT).build(),new Payload(JsonOutput.toJson(claims)))
                jws.sign(new RSASSASigner(key))
                body=JsonOutput.toJson([token_type:'Bearer',access_token:'unused-synthetic-access',id_token:jws.serialize()]).getBytes('UTF-8')
            }else{assert request.uri().path.endsWith('/certs');body=JsonOutput.toJson([keys:[key.toPublicJWK().toJSONObject()]]).getBytes('UTF-8')}
            def response=new FixedResponse(request,body)
            if(token)phase('TOKEN_RESPONSE')
            CompletableFuture.completedFuture(response)
        }
        MigrationManagedProofPort.Observations observations={binding,start,budget->
            // Both concrete provider adapters invoke observation only after strict signed JWT verification.
            phase('JWT_VERIFIED')
            def facts=observer.observe(binding,start,budget)
            phase('OBSERVATION')
            facts
        } as MigrationManagedProofPort.Observations
        MigrationProviderReadinessPort readiness={query,start,budget->
            phase('JWT_VERIFIED')
            def observed=readinessObserver.observe(query,start,budget)
            phase('OBSERVATION')
            observed
        } as MigrationProviderReadinessPort
        def managedSettings=new LegacyMigrationManagedProviderClient.Settings(URI.create(ISSUER),URI.create(ISSUER+'/protocol/openid-connect/token'),URI.create(ISSUER+'/protocol/openid-connect/certs'),'managed-client',URI.create('https://migration.example/migration/managed/callback'))
        def legacySettings=new LegacyMigrationLegacyAccountProviderClient.Settings(URI.create(ISSUER),URI.create(ISSUER+'/protocol/openid-connect/token'),URI.create(ISSUER+'/protocol/openid-connect/certs'),'legacy-client',URI.create('https://migration.example/migration/legacy/account-oidc/callback'))
        managedAdapter=new LegacyMigrationManagedProviderClient(managedSettings,Clock.systemUTC(),readiness,http,new LegacyMigrationOidcClient(new LegacyMigrationOidcClient.Settings(URI.create(ISSUER),URI.create(ISSUER+'/protocol/openid-connect/certs'),'managed-client'),Clock.systemUTC(),http))
        accountAdapter=new LegacyMigrationLegacyAccountProviderClient(legacySettings,Clock.systemUTC(),observations,http,new LegacyMigrationOidcClient(new LegacyMigrationOidcClient.Settings(URI.create(ISSUER),URI.create(ISSUER+'/protocol/openid-connect/certs'),'legacy-client'),Clock.systemUTC(),http))
        MigrationManagedProofPort measuredManaged={attempt,input,sink->
            managedAdapter.exchange(attempt,input,{claims,facts->sink.accept(claims,facts);phase('SINK_ACCEPTED')} as MigrationManagedProofPort.VerifiedSink)
        } as MigrationManagedProofPort
        MigrationLegacyAccountProofPort measuredAccount={attempt,input,sink->
            accountAdapter.exchange(attempt,input,{claims,facts->sink.accept(claims,facts);phase('SINK_ACCEPTED')} as MigrationLegacyAccountProofPort.VerifiedSink)
        } as MigrationLegacyAccountProofPort
        managed=new PostgresLegacyMigrationManagedProofStore(database,custody,crypto,measuredManaged,readiness,new PostgresLegacyMigrationManagedProofStore.Settings(ACTOR,ISSUER,'managed-client','https://migration.example/migration/managed/callback'),true)
        account=new PostgresLegacyMigrationAccountProofStore(database,custody,crypto,measuredAccount,new PostgresLegacyMigrationManagedProofStore.Settings(ACTOR,ISSUER,'legacy-client','https://migration.example/migration/legacy/account-oidc/callback'),true)
    }
    def cleanup(){managedAdapter?.close();accountAdapter?.close();readinessObserver?.close();observer?.close();server?.stop(0);serverThreads?.shutdownNow();serverThreads?.awaitTermination(3,TimeUnit.SECONDS);database?.close();postgres?.stop()}
    long count(String table){database.transaction{sql->sql.fetchOne('select count(*) from '+table).get(0,Long)}}
    def localOwner(){auth.register(EMAIL,PASSWORD,PHONE);def token=auth.login(EMAIL,PASSWORD).accessToken();def original=auth.me('Bearer '+token).id();[identityId:original,token:token,admission:auth.beginLegacyMigration(EMAIL,PASSWORD,UUID.randomUUID(),local)]}
    def historicalSsoOwner(){
        def token=auth.sso('synthetic-old-principal',{ignored->new SsoTokens.Principal(ISSUER,'signed-existing',PHONE, null, false, false)} as SsoTokens).accessToken()
        def original=auth.me('Bearer '+token).id()
        // Published credential pair stays NULL/NULL; user candidate is a separate genuine browser use case.
        [identityId:original,token:token]
    }
    LegacyMigration.Admission ssoAdmissionWithCandidate(MigrationManagedProofStore.Redirect redirect){
        def browser=new PostgresLegacyMigrationBrowserService(database,auth,local,account,managed,crypto,URI.create(ISSUER+'/protocol/openid-connect/auth'),URI.create(ISSUER+'/protocol/openid-connect/auth'),true)
        def restricted=browser.accountOidcCallback(callback(redirect))
        assert restricted.emailCandidateRequired()
        def selected=browser.emailCandidate(restricted.accountCookie(),restricted.csrf(),restricted.commandId(),EMAIL)
        assert !selected.emailCandidateRequired()
        assert selected.expiresAt()==restricted.expiresAt()
        assert count('legacy_migration_owner_contacts')==0
        // Read ONLY metadata of the actual completed account producer. Capability comes from its genuine response.
        def row=database.transaction{sql->sql.fetchOne('select i.* from legacy_migration_intents i join legacy_migration_account_completions c on c.migration_id=i.id where c.attempt_id=?',redirect.attemptId()).intoMap()}
        new LegacyMigration.Admission(row.id as UUID,row.identity_id as UUID,(row.owner_generation as Number).longValue(),(row.credential_generation as Number).longValue(),(row.created_at as OffsetDateTime).toInstant(),(row.authorization_deadline as OffsetDateTime).toInstant(),selected.accountCookie())
    }
    void interactive(MigrationManagedProofStore.Redirect redirect,String table){
        nonce=redirect.nonce()
        def created=database.transaction{sql->sql.fetchOne('select created_at from '+table+' where id=?',redirect.attemptId()).get(0,OffsetDateTime).toInstant()}
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
        while(Instant.now().epochSecond<=created.epochSecond&&System.nanoTime()<limit)Thread.sleep(10)
        assert Instant.now().epochSecond>created.epochSecond
    }
    MigrationManagedProofPort.Callback callback(MigrationManagedProofStore.Redirect redirect){String code='synthetic-'+redirect.attemptId();callbackNonces.put(code,redirect.nonce());callbackSubjects.put(code,signedSubject);new MigrationManagedProofPort.Callback(code,redirect.state(),redirect.attemptCookie())}
    def 'production resources wire the same owned V2 readiness into managed producer and store and close it'(){
        given:
        def configuration=LegacyMigrationConfiguration.from(LegacyMigrationConfigurationSpec.environment(),[:])
        // Borrow this fixture's genuine isolated PostgreSQL/Auth dependencies. No worker is started,
        // no provider response or business authority is fabricated, and construction sends no HTTP.
        def resources=LegacyMigrationResources.open(database,auth,configuration).orElseThrow()
        def field={Object target,String name->
            def member=target.getClass().getDeclaredField(name);member.accessible=true;member.get(target)
        }
        def owners=field(resources,'owned') as List
        def readinessOwners=owners.findAll{it instanceof HttpMigrationProviderReadiness}
        def service=field(resources.routes(),'service')
        def wiredStore=field(service,'managed')
        def wiredProvider=field(wiredStore,'provider')
        def v1=owners.find{it instanceof LegacyMigrationResources.OwnedObservations}
        expect:
        readinessOwners.size()==1
        v1!=null
        !v1.is(readinessOwners.first())
        field(wiredStore,'readiness').is(readinessOwners.first())
        field(wiredProvider,'readiness').is(readinessOwners.first())
        field(field(service,'account'),'provider') instanceof LegacyMigrationLegacyAccountProviderClient
        field(field(field(service,'account'),'provider'),'observations').is(v1)
        !field(readinessOwners.first(),'closed')
        when:resources.close()
        then:field(readinessOwners.first(),'closed')
        when:resources.close()
        then:noExceptionThrown()
        cleanup:resources?.close()
    }
    def 'actual signed local managed proof persists original target and replay observes fresh without second exchange or bank authority'(){
        given:
        def original=localOwner()
        def redirect=managed.start(original.admission.migrationId(),original.admission.ownerCapability(),UUID.randomUUID())
        interactive(redirect,'legacy_migration_managed_attempts')
        when:
        def result=diagnosed { managed.complete(callback(redirect)) }
        phase('RECEIPT_COMMIT')
        then:
        result.state()=='BOUND'
        count('legacy_migration_managed_receipts')==1
        count('legacy_migration_managed_targets')==1
        count('managed_registration_principals')==0
        count('external_identities')==0
        count('legacy_migration_otp_receipts')==0
        auth.me('Bearer '+original.token).id()==original.identityId
        when:
        int exchanges=tokenCalls
        def replay=managed.complete(callback(redirect))
        then:
        replay==result
        tokenCalls==exchanges
        observerCalls>=2
        count('legacy_migration_managed_receipts')==1
        when:
        negative=true
        managed.complete(callback(redirect))
        then:thrown(LegacyMigrationOtp.Failure)
        count('legacy_migration_managed_receipts')==1
        count('managed_registration_principals')==0
        auth.me('Bearer '+original.token).id()==original.identityId
    }
    def 'unverified provider blocks initial managed receipt without revoking either legacy owner mode'(){
        given:
        def original
        if(sso){original=historicalSsoOwner();def accountRedirect=account.start(UUID.randomUUID());interactive(accountRedirect,'legacy_migration_account_attempts');original.admission=ssoAdmissionWithCandidate(accountRedirect)}
        else original=localOwner()
        def redirect=managed.start(original.admission.migrationId(),original.admission.ownerCapability(),UUID.randomUUID())
        interactive(redirect,'legacy_migration_managed_attempts')
        providerEmailVerified=failureMode!='FALSE'
        readinessFault=failureMode
        when:managed.complete(callback(redirect))
        then:thrown(MigrationManagedProofStore.Consumed)
        count('legacy_migration_managed_receipts')==0
        count('legacy_migration_managed_targets')==0
        count('legacy_migration_managed_completions')==0
        count('legacy_migration_final_completions')==0
        count('managed_registration_principals')==0
        auth.me('Bearer '+original.token).id()==original.identityId
        where:
        [sso,failureMode]<<[[false,true],['FALSE','MALFORMED','UNAVAILABLE']].combinations()
    }
    def 'contact mutation after actual readiness HTTP cannot refresh a retained managed receipt'(){
        given:
        def original=localOwner()
        def redirect=managed.start(original.admission.migrationId(),original.admission.ownerCapability(),UUID.randomUUID())
        interactive(redirect,'legacy_migration_managed_attempts')
        def input=callback(redirect)
        managed.complete(input)
        int requests=observerCalls
        MigrationProviderReadinessPort raced={query,start,budget->
            def observed=readinessObserver.observe(query,start,budget)
            // Real mutable identity update runs the published generation triggers after HTTP.
            // No receipt/generation/authority record is fabricated or rewritten.
            database.transaction{sql->sql.execute('update identities set email=? where id=?','changed@example.test',original.identityId)}
            observed
        } as MigrationProviderReadinessPort
        def guarded=new PostgresLegacyMigrationManagedProofStore(database,custody,crypto,managedAdapter,raced,managed.settings(),true)
        when:guarded.complete(input)
        then:thrown(LegacyMigrationOtp.Failure)
        observerCalls==requests+1
        count('legacy_migration_managed_receipts')==1
        count('legacy_migration_managed_completions')==1
        count('legacy_migration_final_completions')==0
        count('managed_registration_principals')==0
        auth.me('Bearer '+original.token).id()==original.identityId
    }
    def 'wrong original browser binder cannot consume claim or invoke provider'(){
        given:
        def original=localOwner()
        def redirect=managed.start(original.admission.migrationId(),original.admission.ownerCapability(),UUID.randomUUID())
        when:managed.complete(new MigrationManagedProofPort.Callback('synthetic-code',redirect.state(),'x'*43))
        then:
        thrown(LegacyMigrationOtp.Failure)
        tokenCalls==0
        count('legacy_migration_managed_claims')==0
        count('legacy_migration_managed_receipts')==0
    }
    def 'genuine signed SSO account proof resolves existing null password owner and same target without new UUID or early revocation'(){
        given:
        def original=historicalSsoOwner()
        def redirect=account.start(UUID.randomUUID())
        interactive(redirect,'legacy_migration_account_attempts')
        when:def admitted=ssoAdmissionWithCandidate(redirect)
        then:
        admitted.identityId()==original.identityId
        count('identities')==1
        count('legacy_migration_account_receipts')==1
        auth.me('Bearer '+original.token).id()==original.identityId
        database.transaction{sql->sql.fetchOne('select email is null and password_hash is null from identities where id=?',original.identityId).get(0,Boolean)}
        when:
        def target=managed.start(admitted.migrationId(),admitted.ownerCapability(),UUID.randomUUID())
        interactive(target,'legacy_migration_managed_attempts')
        def result=managed.complete(callback(target))
        then:
        result.state()=='BOUND'
        count('identities')==1
        count('external_identities')==1
        count('legacy_migration_managed_targets')==1
        count('managed_registration_principals')==0
        auth.me('Bearer '+original.token).id()==original.identityId
    }
    def 'receipt insert rollback emits only fixed transaction phase and preserves claimed owner session'(){
        given:
        def logger=java.util.logging.Logger.getLogger(PostgresLegacyMigrationManagedProofStore.name)
        def records=new CopyOnWriteArrayList<java.util.logging.LogRecord>()
        def handler=new java.util.logging.Handler(){
            void publish(java.util.logging.LogRecord record){records.add(record)}
            void flush(){}
            void close(){}
        }
        def parentHandlers=logger.useParentHandlers
        logger.useParentHandlers=false
        logger.addHandler(handler)
        def original=localOwner()
        def redirect=managed.start(original.admission.migrationId(),original.admission.ownerCapability(),UUID.randomUUID())
        interactive(redirect,'legacy_migration_managed_attempts')
        def input=callback(redirect)
        database.transaction{sql->
            // Fresh isolated fixture: inject a rejecting write, without disabling any invariant.
            sql.execute("create function synthetic_receipt_failure() returns trigger language plpgsql as 'begin raise exception ''SENSITIVE-synthetic-receipt-sentinel'' using errcode = ''40001''; end;'")
            sql.execute('create trigger synthetic_receipt_failure before insert on legacy_migration_managed_receipts for each row execute function synthetic_receipt_failure()')
        }
        when:managed.complete(input)
        then:
        def rejected=thrown(MigrationManagedProofStore.Consumed)
        rejected.error()==LegacyMigrationOtp.Error.UNAVAILABLE
        records*.message==['ARMONEY_MANAGED_STORE_FAILURE_RECEIPT_TRANSACTION']
        records.every{it.thrown==null&&it.parameters==null&&!it.message.contains('SENSITIVE')}
        tokenCalls==1
        count('legacy_migration_managed_claims')==1
        count('legacy_migration_managed_receipts')==0
        count('legacy_migration_managed_targets')==0
        count('legacy_migration_managed_completions')==0
        count('identities')==1
        count('managed_registration_principals')==0
        auth.me('Bearer '+original.token).id()==original.identityId
        when:managed.complete(input)
        then:
        thrown(LegacyMigrationOtp.Failure)
        tokenCalls==1
        records.size()==1
        cleanup:
        logger.removeHandler(handler)
        logger.useParentHandlers=parentHandlers
    }
    def 'unknown genuinely signed legacy subject never allocates an owner or account migration'(){
        given:
        def redirect=account.start(UUID.randomUUID())
        interactive(redirect,'legacy_migration_account_attempts')
        when:diagnosed { account.complete(callback(redirect)) }
        then:
        def failure=thrown(MigrationManagedProofStore.Consumed)
        assert failure.error()==LegacyMigrationOtp.Error.UNAUTHORIZED : phaseSummary()
        tokenCalls==1
        count('identities')==0
        count('legacy_migration_intents')==0
        count('legacy_migration_account_receipts')==0
        count('sessions')==0
    }
    def 'same owner association ABA after genuine account receipt denies managed continuation and old pending continuity'(){
        given:
        def original=historicalSsoOwner()
        def redirect=account.start(UUID.randomUUID())
        interactive(redirect,'legacy_migration_account_attempts')
        def admitted=ssoAdmissionWithCandidate(redirect)
        when:database.transaction{sql->sql.execute('update external_identities set identity_id=identity_id where issuer=? and subject=?',ISSUER,'signed-existing')}
        managed.start(admitted.migrationId(),admitted.ownerCapability(),UUID.randomUUID())
        then:thrown(AuthFailure)
        count('legacy_migration_managed_attempts')==0
        count('sessions')==1
        when:auth.me('Bearer '+original.token)
        then:thrown(AuthFailure)
        count('identities')==1
        count('managed_registration_principals')==0
    }
    def 'concurrent exact callbacks exchange once and persist one original receipt without takeover'(){
        given:
        def original=localOwner()
        def redirect=managed.start(original.admission.migrationId(),original.admission.ownerCapability(),UUID.randomUUID())
        interactive(redirect,'legacy_migration_managed_attempts')
        def input=callback(redirect)
        def ready=new CountDownLatch(1)
        def workers=Executors.newFixedThreadPool(2)
        when:
        def first=workers.submit({ready.await(2,TimeUnit.SECONDS);try{managed.complete(input)}catch(RuntimeException denied){denied.class.name}} as Callable)
        def second=workers.submit({ready.await(2,TimeUnit.SECONDS);try{managed.complete(input)}catch(RuntimeException denied){denied.class.name}} as Callable)
        ready.countDown()
        def results=[first.get(8,TimeUnit.SECONDS),second.get(8,TimeUnit.SECONDS)]
        then:
        results.any{it instanceof MigrationManagedProofStore.Result}
        tokenCalls==1
        count('legacy_migration_managed_claims')==1
        count('legacy_migration_managed_receipts')==1
        count('legacy_migration_managed_targets')==1
        count('managed_registration_principals')==0
        count('identities')==1
        cleanup:
        workers?.shutdownNow()
        workers?.awaitTermination(3,TimeUnit.SECONDS)
    }
    PostgresLegacyMigrationBrowserService browser(boolean enabled=true){new PostgresLegacyMigrationBrowserService(database,auth,local,account,managed,crypto,URI.create(ISSUER+'/protocol/openid-connect/auth'),URI.create(ISSUER+'/protocol/openid-connect/auth'),enabled)}
    def 'browser password uses original server CSRF command genuine checker and single PRIMARY connection with four other pool leases'(){
        given:
        auth.register(EMAIL,PASSWORD,PHONE)
        def oldToken=auth.login(EMAIL,PASSWORD).accessToken()
        def originalId=auth.me('Bearer '+oldToken).id()
        def service=browser()
        def form=service.form(null)
        def occupied=new CountDownLatch(4)
        def release=new CountDownLatch(1)
        def workers=Executors.newFixedThreadPool(5)
        def holders=(1..4).collect{workers.submit({database.withSession{session->occupied.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError('Owned pool fixture release missing');null}} as Callable)}
        assert occupied.await(5,TimeUnit.SECONDS)
        when:
        def operation=workers.submit({service.password(form.formCookie(),form.csrf(),EMAIL,PASSWORD,form.commandId())} as Callable)
        def bound=operation.get(8,TimeUnit.SECONDS)
        release.countDown()
        holders.each{it.get(3,TimeUnit.SECONDS)}
        then:
        bound.commandId()!=form.commandId()
        bound.expiresAt().isAfter(Instant.now())
        count('identities')==1
        count('legacy_migration_intents')==1
        count('legacy_migration_browser_accounts')==1
        count('managed_registration_principals')==0
        auth.me('Bearer '+oldToken).id()==originalId
        cleanup:
        release?.countDown()
        holders?.each{it.get(3,TimeUnit.SECONDS)}
        workers?.shutdownNow()
        workers?.awaitTermination(3,TimeUnit.SECONDS)
    }
    def 'wrong browser CSRF or server command preserves original binder and creates no owner admission'(){
        given:
        auth.register(EMAIL,PASSWORD,PHONE)
        def service=browser()
        def form=service.form(null)
        when:service.password(form.formCookie(),'x'*43,EMAIL,PASSWORD,form.commandId())
        then:
        def rejected=thrown(LegacyMigrationBrowserService.Failure)
        rejected.kind()==LegacyMigrationBrowserService.Kind.UNAUTHORIZED
        !rejected.clearCookies()
        count('legacy_migration_browser_consumptions')==0
        count('legacy_migration_intents')==0
        when:service.password(form.formCookie(),form.csrf(),EMAIL,PASSWORD,UUID.randomUUID())
        then:thrown(LegacyMigrationBrowserService.Failure)
        count('legacy_migration_browser_consumptions')==0
        count('legacy_migration_intents')==0
        when:def result=service.password(form.formCookie(),form.csrf(),EMAIL,PASSWORD,form.commandId())
        then:
        result.accountCookie()!=null
        count('legacy_migration_browser_consumptions')==1
        count('legacy_migration_intents')==1
        count('legacy_migration_managed_attempts')==0
    }
    def 'browser redirect is fixed exact ten field OIDC and active original attempt cannot be silently replaced'(){
        given:
        def service=browser()
        def form=service.form(null)
        when:def result=service.accountOidcStart(form.formCookie(),form.csrf(),form.commandId(),null)
        then:
        result.location().scheme=='https'
        result.location().host=='issuer.example'
        def fields=result.location().rawQuery.split('&').collectEntries{item->def pair=item.split('=',2);[(URLDecoder.decode(pair[0],'UTF-8')):URLDecoder.decode(pair[1],'UTF-8')]}
        fields.keySet()==['client_id','redirect_uri','response_type','scope','state','nonce','code_challenge','code_challenge_method','prompt','max_age'] as Set
        fields.client_id=='legacy-client'
        fields.max_age=='0'
        fields.prompt=='login'
        fields.code_challenge_method=='S256'
        count('legacy_migration_account_attempts')==1
        count('legacy_migration_intents')==0
        when:service.form(result.attemptCookie())
        then:
        def denied=thrown(LegacyMigrationBrowserService.Failure)
        denied.kind()==LegacyMigrationBrowserService.Kind.CONFLICT
        !denied.clearCookies()
        count('legacy_migration_account_attempts')==1
        when:browser(false).form(null)
        then:thrown(LegacyMigrationBrowserService.Failure)
        count('legacy_migration_account_attempts')==1
        tokenCalls==0
    }
    def 'no UUID repeat after genuine negative provider leaves original claim unavailable without duplicate insert or second exchange'(){
        given:
        def original=localOwner()
        def redirect=accountPurpose?account.start(UUID.randomUUID()):managed.start(original.admission.migrationId(),original.admission.ownerCapability(),UUID.randomUUID())
        interactive(redirect,accountPurpose?'legacy_migration_account_attempts':'legacy_migration_managed_attempts')
        def input=callback(redirect)
        negative=true
        when:
        if(accountPurpose)account.complete(input)
        else managed.complete(input)
        then:thrown(MigrationManagedProofStore.Consumed)
        tokenCalls==1
        when:
        if(accountPurpose)account.complete(input)
        else managed.complete(input)
        then:
        def retry=thrown(LegacyMigrationOtp.Failure)
        retry.error()==LegacyMigrationOtp.Error.UNAVAILABLE
        tokenCalls==1
        count(accountPurpose?'legacy_migration_account_claims':'legacy_migration_managed_claims')==1
        count('legacy_migration_managed_receipts')==0
        count('legacy_migration_account_receipts')==0
        count('legacy_migration_managed_targets')==0
        where:accountPurpose << [false,true]
    }
    def 'real two owner User stage and OTP producers select ready B despite earlier unavailable A'(){
        given:
        def userPg=new PostgreSQLContainer('postgres:17.6-alpine');userPg.start()
        def unavailableAuth=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        int decisionCalls=0
        unavailableAuth.createContext('/'){exchange->decisionCalls++;exchange.sendResponseHeaders(503,-1);exchange.close()}
        unavailableAuth.start()
        def repository=java.nio.file.Path.of('').toAbsolutePath()
        if(!java.nio.file.Files.isDirectory(repository.resolve('user-service')))repository=repository.parent
        // This listener never claims a positive decision; User stage is required not to consult final-publication authority.
        def user=LegacyMigrationUserStageFixture.launch(repository,userPg,URI.create('http://127.0.0.1:'+unavailableAuth.address.port))
        def first
        if(unavailable=='AUTH_EXPIRED'){
            def historical=historicalSsoOwner()
            user.bootstrap(historical.identityId,PHONE)
            tokenLifetime=15
            def accountRedirect=account.start(UUID.randomUUID())
            interactive(accountRedirect,'legacy_migration_account_attempts')
            def admitted=ssoAdmissionWithCandidate(accountRedirect)
            first=[identityId:historical.identityId,token:historical.token,admission:admitted]
            tokenLifetime=60
        }else first=localOwner()
        def firstProfile=user.bootstrap(first.identityId,PHONE)
        signedSubject='signed-existing'
        def firstProof=managed.start(first.admission.migrationId(),first.admission.ownerCapability(),UUID.randomUUID())
        interactive(firstProof,'legacy_migration_managed_attempts')
        managed.complete(callback(firstProof))
        auth.register('second-historical@example.test',PASSWORD,'+971521234567')
        def secondToken=auth.login('second-historical@example.test',PASSWORD).accessToken()
        def secondId=auth.me('Bearer '+secondToken).id()
        def secondAdmission=auth.beginLegacyMigration('second-historical@example.test',PASSWORD,UUID.randomUUID(),local)
        def secondProfile=user.bootstrap(secondId,'+971521234567')
        contacts.put('signed-second',[email:'second-historical@example.test',phone:'+971521234567'])
        signedSubject='signed-second'
        def secondProof=managed.start(secondAdmission.migrationId(),secondAdmission.ownerCapability(),UUID.randomUUID())
        interactive(secondProof,'legacy_migration_managed_attempts')
        managed.complete(callback(secondProof))
        def otpCrypto=new LegacyMigrationOtpCrypto('otp',[otp:([83]*32) as byte[]],[otp:([84]*32) as byte[]])
        def otp=new PostgresLegacyMigrationOtpStore(database,custody,otpCrypto,ACTOR,true,managed)
        def service=new LegacyMigrationOtpService(otp,user.stagePort())
        service.stage(first.admission.migrationId(),first.admission.ownerCapability())
        service.stage(secondAdmission.migrationId(),secondAdmission.ownerCapability())
        def challengeA=service.issue(first.admission.migrationId(),first.admission.ownerCapability(),UUID.randomUUID(),LegacyMigrationOtp.Purpose.PHONE)
        def idA=database.transaction{sql->sql.fetchOne('select id from legacy_migration_otp_outbox where challenge_id=?',challengeA.challengeId()).get(0,UUID)}
        if(unavailable=='LEASED'){
            def held=otp.claimDelivery().orElseThrow()
            assert held.deliveryId()==idA
        }else if(unavailable=='RETRY_WAIT'){
            def held=otp.claimDelivery().orElseThrow()
            assert otp.finishDelivery(held.deliveryId(),held.fence(),false)
            // Technical scheduling fault only on a genuinely issued challenge, no possession/ACK seed.
            database.transaction{sql->sql.execute("update legacy_migration_otp_outbox set available_at=clock_timestamp()+interval '60 seconds' where id=?",idA)}
        }else if(unavailable=='EXPIRED'){
            // Expired payload fault only: the original genuine OTP digest/receipt state is not forged.
            database.transaction{sql->sql.execute("update legacy_migration_otp_outbox set expires_at=clock_timestamp()-interval '1 second' where id=?",idA)}
        }
        def challengeB=service.issue(secondAdmission.migrationId(),secondAdmission.ownerCapability(),UUID.randomUUID(),LegacyMigrationOtp.Purpose.PHONE)
        def idB=database.transaction{sql->sql.fetchOne('select id from legacy_migration_otp_outbox where challenge_id=?',challengeB.challengeId()).get(0,UUID)}
        if(unavailable=='AUTH_EXPIRED'){
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
            boolean expiredAccount=false
            while(!expiredAccount&&System.nanoTime()<deadline){
                expiredAccount=database.transaction{sql->sql.fetchOne('select authorization_deadline<=clock_timestamp() from legacy_migration_intents where id=?',first.admission.migrationId()).get(0,Boolean)}
                if(!expiredAccount)Thread.sleep(20)
            }
            assert expiredAccount
            assert database.transaction{sql->sql.fetchOne('select expires_at>clock_timestamp() from legacy_migration_otp_outbox where id=?',idA).get(0,Boolean)}
        }
        observedSubjects.clear()
        when:def selected=otp.claimDelivery().orElseThrow()
        then:
        selected.deliveryId()==idB
        selected.envelope().challengeId()==challengeB.challengeId()
        observedSubjects==['signed-second']
        count('legacy_migration_otp_stage_acks')==2
        count('legacy_migration_otp_receipts')==0
        count('legacy_migration_managed_receipts')==2
        count('managed_registration_principals')==0
        count('identities')==2
        decisionCalls==0
        auth.me('Bearer '+first.token).id()==first.identityId
        auth.me('Bearer '+secondToken).id()==secondId
        cleanup:
        user?.close()
        unavailableAuth?.stop(0)
        userPg?.stop()
        where:unavailable << ['LEASED','RETRY_WAIT','EXPIRED','AUTH_EXPIRED']
    }
    def 'foreign pending or mapped authority after genuine binding denies before observation or stage without revoking stored sessions'(){
        given:
        def original=localOwner()
        def redirect=managed.start(original.admission.migrationId(),original.admission.ownerCapability(),UUID.randomUUID())
        interactive(redirect,'legacy_migration_managed_attempts')
        def input=callback(redirect)
        managed.complete(input)
        int exchanges=tokenCalls
        int observationsBefore=observerCalls
        database.transaction{sql->
            PostgresVerificationProjectionStore.lockControls(sql)
            PostgresLegacyMigrationStore.lockOwner(sql,original.identityId)
            UUID enrollment=UUID.randomUUID()
            def stamp=sql.fetchOne('select clock_timestamp()').get(0,OffsetDateTime)
            // Explicit NEGATIVE association fixture only. No normal receipt/grant/ACK or authenticated migration-association exception is fabricated.
            sql.execute("insert into verification_enrollments(id,issuer,subject,binding_digest,binding_key_id,phone,email,state,created_at,expires_at) values(?,'https://foreign.example/realm','negative-association',?,'negative-fixture',?,?,'PENDING_CONTACTS',cast(? as timestamptz),cast(? as timestamptz))",enrollment,new byte[32],PHONE,EMAIL,stamp,stamp.plusDays(1))
            sql.execute("insert into verification_authorities(enrollment_id,pending_identity_id,mapped_identity_id,state,created_at,expires_at) values(?,?,?,'PENDING_CONTACTS',cast(? as timestamptz),cast(? as timestamptz))",enrollment,original.identityId,mapped?original.identityId:null,stamp,stamp.plusDays(1))
        }
        when:managed.complete(input)
        then:
        def denied=thrown(LegacyMigrationOtp.Failure)
        denied.error()==LegacyMigrationOtp.Error.UNAUTHORIZED
        tokenCalls==exchanges
        observerCalls==observationsBefore
        count('legacy_migration_managed_receipts')==1
        count('legacy_migration_associations')==0
        count('sessions')==1
        count('managed_registration_principals')==0
        when:
        def otp=new PostgresLegacyMigrationOtpStore(database,custody,new LegacyMigrationOtpCrypto('otp',[otp:([83]*32) as byte[]],[otp:([84]*32) as byte[]]),ACTOR,true,managed)
        new LegacyMigrationOtpService(otp).stage(original.admission.migrationId(),original.admission.ownerCapability())
        then:
        def stageDenied=thrown(LegacyMigrationOtp.Failure)
        stageDenied.error()==LegacyMigrationOtp.Error.UNAUTHORIZED
        count('legacy_migration_otp_stages')==0
        count('legacy_migration_otp_receipts')==0
        count('sessions')==1
        where:mapped << [false,true]
    }
    static Map readForm(HttpRequest request){
        def complete=new CompletableFuture<byte[]>()
        def bytes=new ByteArrayOutputStream()
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<java.nio.ByteBuffer>(){
            void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE)}
            void onNext(java.nio.ByteBuffer value){byte[] chunk=new byte[value.remaining()];value.get(chunk);bytes.writeBytes(chunk)}
            void onError(Throwable failure){complete.completeExceptionally(new AssertionError('Synthetic request capture failed'))}
            void onComplete(){complete.complete(bytes.toByteArray())}
        })
        new String(complete.get(1,TimeUnit.SECONDS),'UTF-8').split('&').collectEntries{part->def pair=part.split('=',2);[(URLDecoder.decode(pair[0],'UTF-8')):URLDecoder.decode(pair[1],'UTF-8')]}
    }
    /** Immutable synthetic response; no nested Spock interaction/body variable resolution. */
    static final class FixedResponse implements HttpResponse<byte[]> {
        private final HttpRequest original
        private final byte[] payload
        FixedResponse(HttpRequest request,byte[] body){original=request;payload=body.clone()}
        int statusCode(){200}
        HttpRequest request(){original}
        Optional<HttpResponse<byte[]>> previousResponse(){Optional.empty()}
        HttpHeaders headers(){HttpHeaders.of(['Content-Type':['application/json']],{a,b->true})}
        byte[] body(){payload.clone()}
        Optional<javax.net.ssl.SSLSession> sslSession(){Optional.empty()}
        URI uri(){original.uri()}
        HttpClient.Version version(){HttpClient.Version.HTTP_1_1}
    }
    static final class FixtureHttp extends HttpClient {
        Closure responder
        Optional<java.net.CookieHandler> cookieHandler(){Optional.empty()};Optional<Duration> connectTimeout(){Optional.of(Duration.ofSeconds(2))}
        HttpClient.Redirect followRedirects(){HttpClient.Redirect.NEVER};Optional<java.net.ProxySelector> proxy(){Optional.empty()}
        javax.net.ssl.SSLContext sslContext(){javax.net.ssl.SSLContext.getDefault()};javax.net.ssl.SSLParameters sslParameters(){new javax.net.ssl.SSLParameters()}
        Optional<java.net.Authenticator> authenticator(){Optional.empty()};HttpClient.Version version(){HttpClient.Version.HTTP_1_1};Optional<Executor> executor(){Optional.empty()}
        public <T> HttpResponse<T> send(HttpRequest r,HttpResponse.BodyHandler<T> h){throw new UnsupportedOperationException()}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h){responder.call(r,h) as CompletableFuture<HttpResponse<T>>}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){throw new UnsupportedOperationException()}
        void shutdownNow(){}
    }
}
