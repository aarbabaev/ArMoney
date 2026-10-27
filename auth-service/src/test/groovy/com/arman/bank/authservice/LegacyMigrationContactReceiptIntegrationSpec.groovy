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

/** Rev5 real PG + real RSA verification + actual loopback exact12 HTTP adapter.
 * Token/JWKS transport is explicitly synthetic. Not genuine Keycloak/runtime/TLS/financial acceptance.
 * No SQL stage ACK, provider receipt, grant, contact receipt or managed marker is seeded. */
class LegacyMigrationContactReceiptIntegrationSpec extends Specification {
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
    def fixture=this
    PostgresLegacyMigrationBrowserService browser
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
                if(v2)response=[protocol_version:2,purpose:request.purpose,original_redirect_uri:request.original_redirect_uri,facts:response,provider_email_verified:providerEmailVerified]
                byte[] body=JsonOutput.toJson(response).getBytes('UTF-8')
                exchange.responseHeaders.set('Content-Type','application/json');exchange.sendResponseHeaders(200,body.length);exchange.responseBody.write(body)
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
        browser=new PostgresLegacyMigrationBrowserService(database,auth,local,account,managed,crypto,URI.create(ISSUER+'/protocol/openid-connect/auth'),URI.create(ISSUER+'/protocol/openid-connect/auth'),true)
    }
    def cleanup(){managedAdapter?.close();accountAdapter?.close();readinessObserver?.close();observer?.close();server?.stop(0);serverThreads?.shutdownNow();serverThreads?.awaitTermination(3,TimeUnit.SECONDS);database?.close();postgres?.stop()}
    long count(String table){database.transaction{sql->sql.fetchOne('select count(*) from '+table).get(0,Long)}}
    def localOwner(){auth.register(EMAIL,PASSWORD,PHONE);def token=auth.login(EMAIL,PASSWORD).accessToken();def original=auth.me('Bearer '+token).id();[identityId:original,token:token,admission:auth.beginLegacyMigration(EMAIL,PASSWORD,UUID.randomUUID(),local)]}
    void interactive(MigrationManagedProofStore.Redirect redirect,String table){
        nonce=redirect.nonce()
        def created=database.transaction{sql->sql.fetchOne('select created_at from '+table+' where id=?',redirect.attemptId()).get(0,OffsetDateTime).toInstant()}
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
        while(Instant.now().epochSecond<=created.epochSecond&&System.nanoTime()<limit)Thread.sleep(10)
        assert Instant.now().epochSecond>created.epochSecond
    }
    MigrationManagedProofPort.Callback callback(MigrationManagedProofStore.Redirect redirect){String code='synthetic-'+redirect.attemptId();callbackNonces.put(code,redirect.nonce());callbackSubjects.put(code,signedSubject);new MigrationManagedProofPort.Callback(code,redirect.state(),redirect.attemptCookie())}
 def passwordlessAccount(){
  def token=fixture.auth.sso('synthetic-original',{ignored->new SsoTokens.Principal(fixture.ISSUER,'signed-existing',fixture.PHONE, null, false, false)} as SsoTokens).accessToken()
  def owner=fixture.auth.me('Bearer '+token).id()
  def form=browser.form(null)
  def redirect=browser.accountOidcStart(form.formCookie(),form.csrf(),form.commandId(),null)
  def result=browser.accountOidcCallback(callback(redirect,'legacy_migration_account_attempts'))
  [owner:owner,token:token,account:result]
 }
 MigrationManagedProofPort.Callback callback(LegacyMigrationBrowserService.Redirect redirect,String table){
  def fields=redirect.location().rawQuery.split('&').collectEntries{part->def pair=part.split('=',2);[(URLDecoder.decode(pair[0],'UTF-8')):URLDecoder.decode(pair[1],'UTF-8')]}
  def created=fixture.database.transaction{sql->sql.fetchOne('select max(created_at) from '+table).get(0,OffsetDateTime).toInstant()}
  long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
  while(Instant.now().epochSecond<=created.epochSecond&&System.nanoTime()<stop)Thread.sleep(10)
  assert Instant.now().epochSecond>created.epochSecond
  String code='synthetic-code-'+UUID.randomUUID()
  fixture.callbackNonces.put(code,fields.nonce);fixture.callbackSubjects.put(code,'signed-existing')
  new MigrationManagedProofPort.Callback(code,fields.state,redirect.attemptCookie())
 }
 def freshEvidence(UUID migration,Closure action){
  new PostgresLoginRequestScope(database).inRequest(ACTOR,{owned->
   Instant start=owned.transaction{sql->sql.fetchOne('select clock_timestamp()').get(0,OffsetDateTime).toInstant()}
   managed.withEvidence(owned,migration,start,System.nanoTime(),{evidence->owned.transaction{sql->action.call(sql,evidence)}})
  })
 }
 def 'actual User ACK and delivery worker EMAIL receipt create separate contact without changing credentials or original ACK'(){
  given:
  def userPg=new PostgreSQLContainer('postgres:17.6-alpine');userPg.start()
  def noDecision=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
  int decisionCalls=0
  noDecision.createContext('/'){exchange->decisionCalls++;exchange.sendResponseHeaders(503,-1);exchange.close()}
  noDecision.start()
  def repository=java.nio.file.Path.of('').toAbsolutePath()
  if(!java.nio.file.Files.isDirectory(repository.resolve('user-service')))repository=repository.parent
  def user=LegacyMigrationUserStageFixture.launch(repository,userPg,URI.create('http://127.0.0.1:'+noDecision.address.port))
  def original=passwordlessAccount()
  def profile=user.bootstrap(original.owner,PHONE)
  def selected=browser.emailCandidate(original.account.accountCookie(),original.account.csrf(),original.account.commandId(),EMAIL)
  def target=browser.managedStart(selected.accountCookie(),selected.csrf(),selected.commandId(),null)
  browser.managedCallback(callback(target,'legacy_migration_managed_attempts'))
  UUID migration=database.transaction{sql->sql.fetchOne('select id from legacy_migration_intents where identity_id=?',original.owner).get(0,UUID)}
  def otpCrypto=new LegacyMigrationOtpCrypto('otp',[otp:([83]*32) as byte[]],[otp:([84]*32) as byte[]])
  def otp=new PostgresLegacyMigrationOtpStore(database,custody,otpCrypto,ACTOR,true,managed)
  def service=new LegacyMigrationOtpService(otp,user.stagePort())
  service.stage(migration,selected.accountCookie())
  def originalAck=database.transaction{sql->sql.fetchOne('select ack_json,binding_hash,binding_version from legacy_migration_otp_stage_acks where migration_id=?',migration).intoMap()}
  assert originalAck.binding_version==2
  assert count('legacy_migration_owner_contacts')==0
  def mail=new ArrayBlockingQueue<String>(2)
  def sms=new ArrayBlockingQueue<String>(2)
  server.createContext('/api/messages'){exchange->
   try{
    assert exchange.requestHeaders.getFirst('Authorization')=='Bearer '+('t'*32)
    UUID.fromString(exchange.requestHeaders.getFirst('Idempotency-Key'))
    def body=new JsonSlurper().parse(exchange.requestBody)
    assert body.keySet()==['destination','text','purpose'] as Set
    assert body.purpose=='LEGACY_MIGRATION_PHONE'
    assert body.destination==PHONE
    assert sms.offer(body.text as String)
    byte[] reply=JsonOutput.toJson([id:UUID.randomUUID().toString(),createdAt:Instant.now().toEpochMilli(),replay:false]).getBytes('UTF-8')
    exchange.responseHeaders.set('Content-Type','application/json');exchange.sendResponseHeaders(201,reply.length);exchange.responseBody.write(reply)
   }finally{exchange.close()}
  }
  VerificationDeliveryTransport.MailSender receiver={destination,subject,text,html->
   assert destination==EMAIL
   assert subject=='ArMoney migration email verification'
   assert html.contains('<strong>')
   assert mail.offer(text)
   NotificationDelivery.Result.ACCEPTED
  } as VerificationDeliveryTransport.MailSender
  def transport=new VerificationDeliveryTransport(receiver,URI.create('http://127.0.0.1:'+server.address.port),'t'*32,true)
  def worker=new LegacyMigrationDeliveryWorker(otp,otpCrypto,new LegacyMigrationDeliveryTransport(transport),Clock.systemUTC())
  when:
  def email=service.issue(migration,selected.accountCookie(),UUID.randomUUID(),LegacyMigrationOtp.Purpose.EMAIL)
  worker.tick()
  String rendered=mail.poll(2,TimeUnit.SECONDS)
  assert rendered!=null
  def match=(rendered =~ /\b[0-9]{6}\b/)
  assert match.find()
  String delivered=match.group()
  String wrong=delivered=='000000'?'111111':'000000'
  def incorrect=service.consume(migration,selected.accountCookie(),UUID.randomUUID(),email.challengeId(),LegacyMigrationOtp.Purpose.EMAIL,wrong)
  then:
  incorrect.error()==LegacyMigrationOtp.Error.CONFLICT
  count('legacy_migration_owner_contacts')==0
  when:
  UUID consumeCommand=UUID.randomUUID()
  def accepted=service.consume(migration,selected.accountCookie(),consumeCommand,email.challengeId(),LegacyMigrationOtp.Purpose.EMAIL,delivered)
  then:
  accepted.state()=='CONSUMED'
  count('legacy_migration_owner_contacts')==1
  count('legacy_migration_contact_transitions')==1
  database.transaction{sql->sql.fetchOne('select email is null and password_hash is null from identities where id=?',original.owner).get(0,Boolean)}
  database.transaction{sql->sql.fetchOne('select generation from legacy_migration_owner_contacts where identity_id=?',original.owner).get(0,Long)}==1L
  database.transaction{sql->sql.fetchOne('select ack_json,binding_hash,binding_version from legacy_migration_otp_stage_acks where migration_id=?',migration).intoMap()}==originalAck
  when:def replay=service.consume(migration,selected.accountCookie(),consumeCommand,email.challengeId(),LegacyMigrationOtp.Purpose.EMAIL,delivered)
  then:
  replay==accepted
  count('legacy_migration_contact_transitions')==1
  when:
  freshEvidence(migration,{sql,evidence->otp.requireFinalization(sql,migration,selected.accountCookie(),evidence)})
  then:
  def incomplete=thrown(LegacyMigrationOtp.Failure)
  incomplete.error()==LegacyMigrationOtp.Error.CONFLICT
  count('legacy_migration_contact_transitions')==1
  when:
  def phone=service.issue(migration,selected.accountCookie(),UUID.randomUUID(),LegacyMigrationOtp.Purpose.PHONE)
  worker.tick()
  String smsText=sms.poll(2,TimeUnit.SECONDS)
  assert smsText!=null
  def phoneMatch=(smsText =~ /\b[0-9]{6}\b/)
  assert phoneMatch.find()
  def phoneAccepted=service.consume(migration,selected.accountCookie(),UUID.randomUUID(),phone.challengeId(),LegacyMigrationOtp.Purpose.PHONE,phoneMatch.group())
  then:
  phoneAccepted.state()=='CONSUMED'
  count('legacy_migration_otp_receipts')==2
  count('legacy_migration_owner_contacts')==1
  count('managed_registration_principals')==0
  count('sessions')==1
  auth.me('Bearer '+original.token).id()==original.owner
  decisionCalls==0
  when:
  def threadPool=Executors.newSingleThreadExecutor()
  PostgresLegacyMigrationOtpStore.FinalizationSnapshot escapedFinalization=null
  freshEvidence(migration,{sql,evidence->
   def snapshot=otp.requireFinalization(sql,migration,selected.accountCookie(),evidence)
   snapshot.requireCurrent(sql)
   assert snapshot.source()=='ABSENT_OWNER_CONTACT'
   assert snapshot.baselineGeneration()==0L
   assert snapshot.emailReceiptId()==accepted.receiptId()
   assert snapshot.phoneReceiptId()==phoneAccepted.receiptId()
   assert snapshot.stageAcknowledgement().email_contact_version==1
   assert snapshot.stageAcknowledgement().email_digest==snapshot.candidateDigest()
   assert snapshot.contactTransitionHash()!=null
   def wrongThread=threadPool.submit({try{snapshot.requireCurrent(sql);return false}catch(LegacyMigrationOtp.Failure denied){return denied.error()==LegacyMigrationOtp.Error.UNAVAILABLE}} as Callable<Boolean>)
   assert wrongThread.get(1,TimeUnit.SECONDS)
   int originalBackend=sql.fetchOne('select pg_backend_pid()').get(0,Integer)
   database.withSession{other->other.autocommit{foreignSql->
    assert foreignSql.fetchOne('select pg_backend_pid()').get(0,Integer)!=originalBackend
    try{snapshot.requireCurrent(foreignSql);assert false}catch(LegacyMigrationOtp.Failure denied){assert denied.error()==LegacyMigrationOtp.Error.UNAVAILABLE}
    null
   }}
   snapshot.requireCurrent(sql)
   escapedFinalization=snapshot
   null
  })
  escapedFinalization.requireCurrent(null)
  then:
  def escapedSnapshot=thrown(LegacyMigrationOtp.Failure)
  escapedSnapshot.error()==LegacyMigrationOtp.Error.UNAVAILABLE
  when:
  PostgresLegacyMigrationManagedProofStore.EvidenceUse expiredScope=null
  new PostgresLoginRequestScope(database).inRequest(ACTOR,{owned->
   Instant start=owned.transaction{sql->sql.fetchOne('select clock_timestamp()').get(0,OffsetDateTime).toInstant()}
   managed.withEvidence(owned,migration,start,System.nanoTime(),{evidence->
    expiredScope=evidence
    def wrongThread=threadPool.submit({try{evidence.deadline();return false}catch(LegacyMigrationOtp.Failure expected){return expected.error()==LegacyMigrationOtp.Error.UNAVAILABLE}} as Callable<Boolean>)
    assert wrongThread.get(1,TimeUnit.SECONDS)
    owned.transaction{sql->
     def intent=sql.fetchOne('select * from legacy_migration_intents where id=?',migration)
     def query=new MigrationBindingSqlPort.BindingQuery(migration,original.owner,evidence.receiptId(),evidence.reservationId(),LegacyMigration.ProofMode.LEGACY_OIDC_REAUTH,intent.get('owner_generation',Long),intent.get('association_generation',Long),intent.get('email_generation',Long),intent.get('authorization_deadline',OffsetDateTime).toInstant())
     try{managed.emailConsumed(sql,query,evidence,UUID.randomUUID(),email.challengeId(),UUID.randomUUID());assert false}catch(LegacyMigrationOtp.Failure denied){assert denied.error()==LegacyMigrationOtp.Error.CONFLICT}
     null
    }
    null
   })
  })
  expiredScope.deadline()
  then:
  def escaped=thrown(LegacyMigrationOtp.Failure)
  escaped.error()==LegacyMigrationOtp.Error.UNAVAILABLE
  count('legacy_migration_owner_contacts')==1
  count('legacy_migration_contact_transitions')==1
  when:
  freshEvidence(migration,{sql,evidence->
   def snapshot=otp.requireFinalization(sql,migration,selected.accountCookie(),evidence)
   long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
   while(sql.fetchOne('select clock_timestamp()').get(0,OffsetDateTime).toInstant().isBefore(snapshot.deadline())&&System.nanoTime()<stop)Thread.sleep(10)
   assert !sql.fetchOne('select clock_timestamp()').get(0,OffsetDateTime).toInstant().isBefore(snapshot.deadline())
   snapshot.requireCurrent(sql)
  })
  then:
  def expiredEvidence=thrown(LoginProof.Failure)
  expiredEvidence.error()==LoginProof.Error.UNAVAILABLE
  count('legacy_migration_contact_transitions')==1
  count('legacy_migration_otp_receipts')==2
  cleanup:
  threadPool?.shutdownNow();threadPool?.awaitTermination(2,TimeUnit.SECONDS);worker?.close();transport?.close();user?.close();noDecision?.stop(0);userPg?.stop()
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
