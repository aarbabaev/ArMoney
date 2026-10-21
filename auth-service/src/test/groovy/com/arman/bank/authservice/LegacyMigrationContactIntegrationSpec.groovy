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
class LegacyMigrationContactIntegrationSpec extends Specification {
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
                assert request.keySet()==['issuer','subject','client_id','user_session_id'] as Set
                observerCalls++
                observedSubjects.add(request.subject as String)
                def contact=contacts.get(request.subject as String)
                assert contact!=null
                long observed=Instant.now().epochSecond
                def response=[issuer:request.issuer,subject:request.subject,client_id:request.client_id,user_session_id:request.user_session_id,
                    username:contact.phone,email:contact.email,phone_number:contact.phone,enabled:!negative,user_session_active:true,client_session_active:true,observed_at:Instant.ofEpochSecond(observed).toString(),valid_until:Instant.ofEpochSecond(observed+5).toString()]
                byte[] body=JsonOutput.toJson(response).getBytes('UTF-8')
                exchange.responseHeaders.set('Content-Type','application/json');exchange.sendResponseHeaders(200,body.length);exchange.responseBody.write(body)
            }finally{exchange.close()}
        }
        server.start()
        contacts.put('signed-existing',[email:EMAIL,phone:PHONE])
        def scopes=new CodeIntentPairScopes(JsonOutput.toJson([[issuer:ISSUER,client_id:'legacy-client'],[issuer:ISSUER,client_id:'managed-client']]),'[]')
        observer=new HttpCodeSessionObservations(URI.create('http://127.0.0.1:'+server.address.port),'s'*32,scopes,HttpCodeSessionObservations.privateClient())
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
        def managedSettings=new LegacyMigrationManagedProviderClient.Settings(URI.create(ISSUER),URI.create(ISSUER+'/protocol/openid-connect/token'),URI.create(ISSUER+'/protocol/openid-connect/certs'),'managed-client',URI.create('https://migration.example/migration/managed/callback'))
        def legacySettings=new LegacyMigrationLegacyAccountProviderClient.Settings(URI.create(ISSUER),URI.create(ISSUER+'/protocol/openid-connect/token'),URI.create(ISSUER+'/protocol/openid-connect/certs'),'legacy-client',URI.create('https://migration.example/migration/legacy/account-oidc/callback'))
        managedAdapter=new LegacyMigrationManagedProviderClient(managedSettings,Clock.systemUTC(),observations,http,new LegacyMigrationOidcClient(new LegacyMigrationOidcClient.Settings(URI.create(ISSUER),URI.create(ISSUER+'/protocol/openid-connect/certs'),'managed-client'),Clock.systemUTC(),http))
        accountAdapter=new LegacyMigrationLegacyAccountProviderClient(legacySettings,Clock.systemUTC(),observations,http,new LegacyMigrationOidcClient(new LegacyMigrationOidcClient.Settings(URI.create(ISSUER),URI.create(ISSUER+'/protocol/openid-connect/certs'),'legacy-client'),Clock.systemUTC(),http))
        MigrationManagedProofPort measuredManaged={attempt,input,sink->
            managedAdapter.exchange(attempt,input,{claims,facts->sink.accept(claims,facts);phase('SINK_ACCEPTED')} as MigrationManagedProofPort.VerifiedSink)
        } as MigrationManagedProofPort
        MigrationLegacyAccountProofPort measuredAccount={attempt,input,sink->
            accountAdapter.exchange(attempt,input,{claims,facts->sink.accept(claims,facts);phase('SINK_ACCEPTED')} as MigrationLegacyAccountProofPort.VerifiedSink)
        } as MigrationLegacyAccountProofPort
        managed=new PostgresLegacyMigrationManagedProofStore(database,custody,crypto,measuredManaged,observations,new PostgresLegacyMigrationManagedProofStore.Settings(ACTOR,ISSUER,'managed-client','https://migration.example/migration/managed/callback'),true)
        account=new PostgresLegacyMigrationAccountProofStore(database,custody,crypto,measuredAccount,new PostgresLegacyMigrationManagedProofStore.Settings(ACTOR,ISSUER,'legacy-client','https://migration.example/migration/legacy/account-oidc/callback'),true)
        browser=new PostgresLegacyMigrationBrowserService(database,auth,local,account,managed,crypto,URI.create(ISSUER+'/protocol/openid-connect/auth'),URI.create(ISSUER+'/protocol/openid-connect/auth'),true)
    }
    def cleanup(){managedAdapter?.close();accountAdapter?.close();observer?.close();server?.stop(0);serverThreads?.shutdownNow();serverThreads?.awaitTermination(3,TimeUnit.SECONDS);database?.close();postgres?.stop()}
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
 def localBrowserOwner(){
  auth.register(EMAIL,PASSWORD,PHONE)
  def token=auth.login(EMAIL,PASSWORD).accessToken()
  def owner=auth.me('Bearer '+token).id()
  def before=database.transaction{sql->sql.fetchOne('select password_hash from identities where id=?',owner).get(0,String)}
  def form=browser.form(null)
  def selected=browser.password(form.formCookie(),form.csrf(),EMAIL,PASSWORD,form.commandId())
  [owner:owner,token:token,passwordHash:before,account:selected]
 }
 boolean commandConsumed(UUID command){database.transaction{sql->sql.fetchOne('select count(*) from legacy_migration_browser_consumptions where command_id=?',command).get(0,Long)}!=0}
 void preservedLocalOwner(Map original){
  assert auth.me('Bearer '+original.token).id()==original.owner
  assert database.transaction{sql->sql.fetchOne('select password_hash from identities where id=?',original.owner).get(0,String)}==original.passwordHash
  assert count('managed_registration_principals')==0
  assert count('legacy_migration_managed_receipts')==0
 }
 def 'composed password account starts a managed attempt without changing legacy credentials or session'(){
  given:def original=localBrowserOwner()
  expect:!original.account.emailCandidateRequired()
  when:def redirect=browser.managedStart(original.account.accountCookie(),original.account.csrf(),original.account.commandId(),null)
  then:
  redirect.location().host=='issuer.example'
  count('legacy_migration_managed_attempts')==1
  commandConsumed(original.account.commandId())
  preservedLocalOwner(original)
  when:browser.managedStart(original.account.accountCookie(),original.account.csrf(),original.account.commandId(),null)
  then:
  def rejected=thrown(LegacyMigrationBrowserService.Failure)
  rejected.kind()==LegacyMigrationBrowserService.Kind.CONFLICT
  count('legacy_migration_managed_attempts')==1
 }
 // REC-AUTH-HTTP-02: the dedicated Gateway validates HTML content type on redirects too.
 def 'actual HTTP managed redirect declares the gateway required HTML content type'(){
  given:
  def original=localBrowserOwner()
  def origin=URI.create('https://migration.example')
  def endpoint=URI.create(ISSUER+'/protocol/openid-connect/auth')
  def configuration=new LegacyMigrationBrowserConfiguration(true,origin,
   origin.resolve(LegacyMigrationBrowserConfiguration.LEGACY_CALLBACK),origin.resolve(LegacyMigrationBrowserConfiguration.MANAGED_CALLBACK),
   endpoint,'legacy-client',endpoint,'managed-client')
  def ingress=LegacyMigrationBrowserIngressConfiguration.from([AUTH_MIGRATION_BROWSER_ENABLED:'true',
   MIGRATION_PUBLIC_ORIGIN:origin.toString(),MIGRATION_GATEWAY_TO_AUTH_KEY:'g'*64])
  def routes=new LegacyMigrationBrowserRoutes(configuration,ingress,browser)
  def app=io.javalin.Javalin.create{config->routes.configure(config)}.start('127.0.0.1',0)
  def client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build()
  def body='csrf='+original.account.csrf()+'&command_id='+original.account.commandId()
  def request=HttpRequest.newBuilder(URI.create('http://127.0.0.1:'+app.port()+LegacyMigrationBrowserConfiguration.MANAGED_START))
   .timeout(Duration.ofSeconds(5)).header('Origin',origin.toString())
   .header('X-ArMoney-Migration-Auth-Key','g'*64).header('X-ArMoney-Migration-Origin',origin.toString())
   .header('Cookie',LegacyMigrationBrowserRoutes.ACCOUNT_COOKIE+'='+original.account.accountCookie())
   .header('Content-Type','application/x-www-form-urlencoded').POST(HttpRequest.BodyPublishers.ofString(body)).build()
  when:
  def response=client.send(request,HttpResponse.BodyHandlers.ofString())
  int status=response.statusCode()
  int contentTypeCount=response.headers().allValues('Content-Type').size()
  String contentType=response.headers().firstValue('Content-Type').orElse('')
  int locationCount=response.headers().allValues('Location').size()
  boolean bodyEmpty=response.body().isEmpty()
  then:
  status==303
  contentTypeCount==1
  contentType.matches('(?i)text/html(?:;\\s*charset=UTF-8)?')
  locationCount==1
  bodyEmpty
  commandConsumed(original.account.commandId())
  preservedLocalOwner(original)
  cleanup:client?.close();app?.stop()
 }
 // REC-SEC-AUTH-01: confirmed rollback must not burn the original command.
 def 'managed allocation rollback preserves the exact browser command for retry and the legacy owner session'(){
  given:
  def original=localBrowserOwner()
  database.transaction{sql->
   sql.execute("CREATE FUNCTION synthetic_managed_start_failure() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''Synthetic managed allocation unavailable'' USING ERRCODE=''40001''; END;'")
   sql.execute('CREATE TRIGGER synthetic_managed_start_failure BEFORE INSERT ON legacy_migration_managed_attempts FOR EACH ROW EXECUTE FUNCTION synthetic_managed_start_failure()')
   null
  }
  when:browser.managedStart(original.account.accountCookie(),original.account.csrf(),original.account.commandId(),null)
  then:
  thrown(org.jooq.exception.DataAccessException)
  !commandConsumed(original.account.commandId())
  count('legacy_migration_managed_attempts')==0
  preservedLocalOwner(original)
  when:
  database.transaction{sql->sql.execute('DROP TRIGGER synthetic_managed_start_failure ON legacy_migration_managed_attempts');null}
  def redirect=browser.managedStart(original.account.accountCookie(),original.account.csrf(),original.account.commandId(),null)
  then:
  redirect.location().host=='issuer.example'
  commandConsumed(original.account.commandId())
  count('legacy_migration_managed_attempts')==1
  preservedLocalOwner(original)
 }
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
 def 'passwordless signed owner must explicitly select candidate and preserves original credentials and UUID'(){
  given:def original=passwordlessAccount()
  expect:
  original.account.emailCandidateRequired()
  fixture.count('legacy_migration_owner_contacts')==0
  fixture.database.transaction{sql->sql.fetchOne('select email is null and password_hash is null from identities where id=?',original.owner).get(0,Boolean)}
  when:browser.managedStart(original.account.accountCookie(),original.account.csrf(),original.account.commandId(),null)
  then:thrown(LegacyMigrationBrowserService.Failure)
  when:def selected=browser.emailCandidate(original.account.accountCookie(),original.account.csrf(),original.account.commandId(),fixture.EMAIL)
  then:
  !selected.emailCandidateRequired()
  selected.expiresAt()==original.account.expiresAt()
  fixture.count('legacy_migration_email_candidates')==1
  fixture.count('legacy_migration_owner_contacts')==0
  fixture.auth.me('Bearer '+original.token).id()==original.owner
  when:def replay=browser.emailCandidate(original.account.accountCookie(),original.account.csrf(),original.account.commandId(),fixture.EMAIL)
  then:replay==selected
  when:browser.emailCandidate(original.account.accountCookie(),original.account.csrf(),original.account.commandId(),'different@example.test')
  then:thrown(LegacyMigrationBrowserService.Failure)
  when:
  def target=browser.managedStart(selected.accountCookie(),selected.csrf(),selected.commandId(),null)
  def result=browser.managedCallback(callback(target,'legacy_migration_managed_attempts'))
  then:
  result.state()=='BOUND'
  fixture.count('legacy_migration_managed_receipts')==1
  fixture.count('legacy_migration_owner_contacts')==0
  fixture.count('managed_registration_principals')==0
  fixture.database.transaction{sql->sql.fetchOne('select email is null and password_hash is null from identities where id=?',original.owner).get(0,Boolean)}
 }
 def 'wrong email form purpose and CSRF cannot allocate candidate or consume valid original form'(){
  given:def original=passwordlessAccount()
  when:browser.emailCandidate(original.account.accountCookie(),'x'*43,original.account.commandId(),fixture.EMAIL)
  then:thrown(LegacyMigrationBrowserService.Failure)
  fixture.count('legacy_migration_email_candidates')==0
  when:def selected=browser.emailCandidate(original.account.accountCookie(),original.account.csrf(),original.account.commandId(),fixture.EMAIL)
  then:!selected.emailCandidateRequired()
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
