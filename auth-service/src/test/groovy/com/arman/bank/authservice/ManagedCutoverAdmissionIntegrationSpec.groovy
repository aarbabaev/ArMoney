package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.*
import com.arman.bank.runtime.ServiceRuntime
import groovy.json.JsonOutput
import spock.lang.Specification
import java.net.http.*
import java.time.*

/** Real disposable PostgreSQL and Auth HTTP routes. Provider principals are synthetic, not Keycloak attack evidence. */
class ManagedCutoverAdmissionIntegrationSpec extends Specification {
    static final String ISSUER='https://issuer.example/realm'
    static final String KEY='synthetic-cutover-service-key-32-characters'
    LegacyMigrationIntegrationSpec fixture
    ServiceRuntime runtime
    HttpClient client
    def profiles=Mock(RegistrationProfiles)
    def banking=Mock(ManagedBankingStore)
    def observations=Mock(CodeConfirmationObservations)

    def setup() {
        fixture=new LegacyMigrationIntegrationSpec();fixture.setup()
        client=HttpClient.newHttpClient()
    }
    def cleanup() { client?.close();runtime?.close();fixture?.cleanup() }
    SsoTokens.Principal principal() {
        new SsoTokens.Principal(ISSUER,'existing-legacy-subject','+971521234567','sso@example.test',true,
            new SsoTokens.TokenContext(SsoTokens.Metadata.present('armoney-web'),SsoTokens.Metadata.present('legacy-session'),
                SsoTokens.Metadata.present(Instant.now()),SsoTokens.Metadata.present(Instant.now().plusSeconds(120)),SsoTokens.Metadata.absent()))
    }
    AuthService service(boolean enabled=true) {
        new AuthService(fixture.store,new Argon2Passwords(),Clock.systemUTC(),profiles,
            new ManagedBankingService(enabled,banking,observations,{issuer,who->issuer==ISSUER&&who=='armoney-android'} as ManagedBankingService.PairScope),
            {issuer,who->issuer==ISSUER&&who=='armoney-web'})
    }
    void serve(AuthService auth) {
        def routes=new AuthRoutes(auth,KEY,{token->principal()} as SsoTokens)
        runtime=ServiceRuntime.start('auth-service',0,fixture.database,routes::configure)
    }
    def post(String path,Map body) {
        def request=HttpRequest.newBuilder(URI.create("http://localhost:${runtime.port()}"+path))
            .timeout(Duration.ofSeconds(10)).header('X-Service-Key',KEY).header('Content-Type','application/json')
            .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body))).build()
        client.send(request,HttpResponse.BodyHandlers.ofString())
    }
    def snapshot() {
        fixture.database.transaction { sql ->
            ['identities','external_identities','sessions','auth_attempts','legacy_auth_generations','legacy_auth_association_generations',
             'verification_authorities','banking_eligibility_grants','legacy_migration_intents','managed_banking_sessions'].collectEntries { table ->
                [(table):sql.fetch('select to_jsonb(t)::text from '+table+' t order by 1').getValues(0,String)]
            }
        }
    }

    def 'enabled cutover rejects valid legacy SSO with existing owner but no authority grant or intent without business mutation'() {
        given:
        def old=fixture.auth.sso('synthetic.legacy.token',{token->principal()} as SsoTokens)
        def owner=fixture.auth.me('Bearer '+old.accessToken()).id()
        assert fixture.count('verification_authorities')==0
        assert fixture.count('banking_eligibility_grants')==0
        assert fixture.count('legacy_migration_intents')==0
        def before=snapshot();def auth=service();serve(auth)
        when:def response=post('/v1/auth/sso',[access_token:'synthetic.legacy.token'])
        then:
        response.statusCode()==401;response.body()=='{"error":"invalid_credentials"}'
        snapshot()==before
        auth.me('Bearer '+old.accessToken()).id()==owner
        auth.me('Bearer '+fixture.oldToken).id()==fixture.identityId
        0 * profiles._;0 * banking._;0 * observations._
    }

    def 'enabled cutover rejects new and retry local registration and all ordinary local login without business mutation'() {
        given:def auth=service();serve(auth);def before=snapshot()
        when:
        def responses=[post('/v1/auth/register',[email:'new@example.test',password:fixture.PASSWORD,phone_number:'+971541234567']),
            post('/v1/auth/register',[email:'legacy@example.test',password:fixture.PASSWORD,phone_number:'+971501234567']),
            post('/v1/auth/login',[email:'legacy@example.test',password:fixture.PASSWORD]),
            post('/v1/auth/login',[email:'unknown@example.test',password:fixture.PASSWORD]),
            post('/v1/auth/login',[email:'legacy@example.test',password:'wrong synthetic long password'])]
        then:
        responses.every{it.statusCode()==401&&it.body()=='{"error":"invalid_credentials"}'}
        snapshot()==before
        auth.me('Bearer '+fixture.oldToken).id()==fixture.identityId
        0 * profiles._;0 * banking._;0 * observations._
        when:def admission=auth.beginLegacyMigration('legacy@example.test',fixture.PASSWORD,UUID.randomUUID(),fixture.migrations)
        then:
        admission.identityId()==fixture.identityId
        fixture.count('legacy_migration_intents')==1
        fixture.count('sessions')==1
        auth.me('Bearer '+fixture.oldToken).id()==fixture.identityId
    }

    def 'default off keeps local registration login and explicit legacy SSO working'() {
        given:def auth=service(false)
        when:
        auth.register('new@example.test',fixture.PASSWORD,'+971541234567')
        def local=auth.login('new@example.test',fixture.PASSWORD)
        def old=auth.sso('synthetic.legacy.token',{token->principal()} as SsoTokens)
        then:
        auth.me('Bearer '+local.accessToken()).email()=='new@example.test'
        auth.me('Bearer '+old.accessToken()).id()!=fixture.identityId
        fixture.count('sessions')==3
        fixture.count('external_identities')==1
        fixture.count('legacy_migration_intents')==0
        2 * profiles.provision(_, '+971541234567')
        1 * profiles.provision(_, '+971521234567')
        0 * banking._;0 * observations._
    }
}
