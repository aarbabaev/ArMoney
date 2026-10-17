package com.arman.bank.authservice

import com.arman.bank.authservice.infrastructure.*
import com.arman.bank.runtime.Database
import io.javalin.Javalin
import groovy.json.*
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.net.http.*
import java.time.*

/** Actual stage HTTP/config/store on isolated PG. Synthetic marker is not positive authority. */
class CodeIntentHttpIntegrationSpec extends Specification {
    PostgreSQLContainer postgres
    Database database
    CodeIntentConfiguration.Resources resources
    Javalin app
    HttpClient client=HttpClient.newHttpClient()
    Map environment=CodeIntentConfigurationSpec.environment()
    UUID identity=UUID.randomUUID()
    UUID enrollment=UUID.randomUUID()
    def setup(){
        postgres=new PostgreSQLContainer('postgres:17.6-alpine');postgres.start()
        database=new Database(postgres.jdbcUrl,postgres.username,postgres.password)
        seed(database,environment.SSO_ISSUER,identity,enrollment)
        open(environment)
    }
    static void seed(Database database,String issuer,UUID identity,UUID enrollment,boolean managed=true,String subject='managed-subject'){
        database.transaction{sql->
            sql.execute('insert into identities(id) values (?)',identity)
            sql.execute('insert into external_identities values (?,?,?)',issuer,subject,identity)
            sql.execute('''insert into verification_enrollments(id,issuer,subject,binding_digest,binding_key_id,phone,email,state,created_at,expires_at)
                values (?,?,?,?,'synthetic','+971501234567','synthetic@example.test','PENDING_CONTACTS',clock_timestamp(),clock_timestamp()+interval '1 day')''',enrollment,issuer,subject,new byte[32])
            sql.execute('''insert into verification_authorities(enrollment_id,pending_identity_id,mapped_identity_id,state,created_at,expires_at)
                values (?,?,?,'PROJECTION_PENDING',statement_timestamp(),statement_timestamp()+interval '86400 seconds')''',enrollment,identity,identity)
            if(managed)sql.execute('insert into managed_registration_principals(identity_id,enrollment_id) values (?,?)',identity,enrollment)
        }
    }
    void open(Map env){
        app?.stop();resources?.close()
        resources=new CodeIntentConfiguration(env).open(database).orElseThrow()
        resources.store().cleanup()
        app=Javalin.create{resources.routes().configure(it)}.start('127.0.0.1',0)
    }
    def cleanup(){app?.stop();resources?.close();client?.close();database?.close();postgres?.stop()}
    static Map command(String issuer){
        def now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        CodeIntentRoutesSpec.command()+[issuer:issuer,code_key_id:'code-v1',issued_at:now.toString(),expires_at:now.plusSeconds(120).toString(),password_authenticated_at:now.toString()]
    }
    def post(Map input,String key=environment.SSO_LOGIN_PROOF_WRITE_KEY){postRaw(JsonOutput.toJson(input),key)}
    def postRaw(String body,String key=environment.SSO_LOGIN_PROOF_WRITE_KEY){
        def builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:${app.port()}/internal/sso-code-intents")).timeout(Duration.ofSeconds(6)).header('Content-Type','application/json')
        if(key!=null)builder.header('X-ArMoney-Login-Proof-Write-Key',key)
        client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString())
    }
    long count(String table){database.transaction{it.fetchOne('select count(*) from '+table).get(0,Long)}}
    def retained(){database.transaction{sql->['sso_code_intents','sso_code_intent_details','sso_code_intent_commands'].collectEntries{table->[(table):sql.fetch('select to_jsonb(t)::text from '+table+' t order by 1').getValues(0,String)]}}}
    boolean noAuthority(){['sso_login_proofs','sso_login_events','sso_login_proof_commands','sessions','verification_delivery_outbox','banking_eligibility_grants'].every{count(it)==0}}
    def 'real stage HTTP commits immutable intent only and exact lost ACK retry returns original five fields'(){
        given:def input=command(environment.SSO_ISSUER)
        when:def first=post(input);def snapshot=retained();def retry=post(input)
        then:
        first.statusCode()==200;retry.statusCode()==200;retry.body()==first.body();retained()==snapshot
        new JsonSlurper().parseText(first.body()).keySet()==['intent_id','code_key_id','state','issued_at','expires_at'] as Set
        count('sso_code_intents')==1;count('sso_code_intent_commands')==1;count('sso_code_intent_nonces')==1
        noAuthority()
        when:def conflict=post(input+[event_id:UUID.randomUUID().toString()])
        then:conflict.statusCode()==409;retained()==snapshot;noAuthority()
    }
    def 'transport credentials cannot spend budget and authenticated malformed input does'(){
        expect:postRaw('not-json','wrong').statusCode()==401;count('sso_login_proof_admission')==0
        when:def malformed=postRaw('not-json')
        then:malformed.statusCode()==400;count('sso_login_proof_admission')==1;count('sso_code_intents')==0;noAuthority()
    }
    def 'unknown subject or missing permanent marker cannot be inferred as managed from HTTP'(){
        given:
        def input=command(environment.SSO_ISSUER)
        if(mode=='marker'){input.subject='unclassified-subject';seed(database,environment.SSO_ISSUER,UUID.randomUUID(),UUID.randomUUID(),false,input.subject)}
        else input.subject='unknown-subject'
        when:def response=post(input)
        then:response.statusCode()==409;count('sso_code_intents')==0;count('sso_code_intent_nonces')==0;noAuthority()
        where:mode << ['subject','marker']
    }
    def 'original retained HMAC key selects exact replay after rotation while missing original key denies'(){
        given:def input=command(environment.SSO_ISSUER);def first=post(input);assert first.statusCode()==200;def frozen=retained()
        def rotated=environment+[SSO_LOGIN_PROOF_RETENTION_HMAC_KEY_ID:'stage-v2',SSO_LOGIN_PROOF_RETENTION_HMAC_KEY:CodeIntentConfigurationSpec.encoded(25),
            SSO_LOGIN_PROOF_RETENTION_HMAC_PREVIOUS_KEYS:'stage-v1:'+environment.SSO_LOGIN_PROOF_RETENTION_HMAC_KEY]
        when:open(rotated);def replay=post(input)
        then:replay.statusCode()==200;replay.body()==first.body();retained()==frozen;noAuthority()
        when:open(rotated+[SSO_LOGIN_PROOF_RETENTION_HMAC_PREVIOUS_KEYS:'']);def missing=post(input)
        then:missing.statusCode()==503;retained()==frozen;noAuthority()
    }
    def 'all sixteen global permits reject stage before parsing and retain no new state'(){
        given:def held=[]
        (0..<16).each{slot->
            def connection=java.sql.DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password);held.add(connection)
            def statement=connection.prepareStatement('select pg_try_advisory_lock(?,?)')
            try{statement.setInt(1,0x41524D4F);statement.setInt(2,slot);def result=statement.executeQuery();try{assert result.next();assert result.getBoolean(1)}finally{result.close()}}finally{statement.close()}
        }
        when:def response=postRaw('not-json')
        then:response.statusCode()==429;count('sso_login_proof_admission')==0;count('sso_code_intents')==0;noAuthority()
        cleanup:held.each{it.close()}
    }
}
