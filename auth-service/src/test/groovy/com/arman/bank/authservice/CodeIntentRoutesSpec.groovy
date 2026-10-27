package com.arman.bank.authservice

import com.arman.bank.authservice.application.CodeIntentStore
import com.arman.bank.authservice.domain.CodeIntent
import com.arman.bank.authservice.domain.LoginProof.*
import com.arman.bank.authservice.domain.LoginProof.Error
import com.arman.bank.authservice.infrastructure.CodeIntentRoutes
import io.javalin.Javalin
import groovy.json.*
import spock.lang.Specification
import java.net.http.*
import java.time.*

class CodeIntentRoutesSpec extends Specification {
    static final String KEY='synthetic-stage-directional-write-credential'
    static final String ISSUER='https://issuer.example/realm'
    CodeIntentStore store=Mock()
    CodeIntentStore.Request scoped=Mock()
    def policy=Policy.defaults([ISSUER] as Set,['armoney-web'] as Set,['code1'] as Set,['provider'] as Set)
    Javalin app
    HttpClient client=HttpClient.newHttpClient()
    def records=new java.util.concurrent.CopyOnWriteArrayList<java.util.logging.LogRecord>()
    def logger=java.util.logging.Logger.getLogger(CodeIntentRoutes.name)
    def handler=new java.util.logging.Handler(){void publish(java.util.logging.LogRecord record){records.add(record)};void flush(){};void close(){}}
    def setup(){logger.addHandler(handler);app=Javalin.create{new CodeIntentRoutes(store,policy,'provider',KEY).configure(it)}.start('127.0.0.1',0)}
    def cleanup(){logger.removeHandler(handler);app?.stop();client?.close()}
    def 'failure diagnostics contain only closed categories without changing response or admission count'(){
        when:def response=post(JsonOutput.toJson(command()))
        then:
        1 * store.inRequest('provider',_) >> {throw failure}
        0 * scoped.stage(_)
        response.statusCode()==status
        response.body()=='{"error":"code_intent_request_rejected"}'
        records*.message==['armoney_auth_stage_failure category='+category]
        records.every{it.thrown==null && !it.parameters}
        where:
        failure | status | category
        new Failure(Error.UNAVAILABLE) | 503 | 'DOMAIN_UNAVAILABLE'
        new Failure(Error.CONFLICT) | 409 | 'DOMAIN_CONFLICT'
        new IllegalStateException('private credential must not be logged') | 503 | 'INTERNAL'
    }
    static Map command(){[command_id:UUID.randomUUID().toString(),code_digest:'a'*64,code_key_id:'code1',issuer:ISSUER,subject:'managed-subject',
        client_id:'armoney-web',user_session_id:'session',authentication_session_id:'authentication',issued_at:'2026-10-17T00:00:00Z',
        expires_at:'2026-10-17T00:02:00Z',kind:'interactive',event_id:UUID.randomUUID().toString(),password_authenticated_at:'2026-10-17T00:00:00Z']}
    def post(String body,String key=KEY,String path='/internal/sso-code-intents',String query=''){
        def request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:${app.port()}${path}${query}")).timeout(Duration.ofSeconds(6)).header('Content-Type','application/json')
        if(key!=null)request.header('X-ArMoney-Login-Proof-Write-Key',key)
        client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString())
    }
    def 'wrong missing and resolver-only credentials deny before admission or parsing'(){
        when:def response=post('not-json',key)
        then:response.statusCode()==401;0 * store._
        where:key << [null,'synthetic-resolver-only-credential','wrong']
    }
    def 'malformed strict stage schema is admitted exactly once and never reaches stage storage'(){
        when:def response=post(body)
        then:
        1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function operation->operation.apply(scoped)}
        0 * scoped.stage(_)
        response.statusCode()==400
        response.body()=='{"error":"code_intent_request_rejected"}'
        response.headers().firstValue('Cache-Control').get()=='no-store'
        where:body << ['{}','{"issuer":"a","issuer":"b"}',JsonOutput.toJson(command())+' {}','x'*12289,
            JsonOutput.toJson(command()+[identity_id:UUID.randomUUID().toString()]),JsonOutput.toJson(command()+[event_id:null]),
            JsonOutput.toJson(command()+[password_authenticated_at:false]),JsonOutput.toJson(command()+[subject:'bad subject']),
            JsonOutput.toJson(command()+[issued_at:'2026-10-17T00:00:00.000Z'])]
    }
    def 'stage response is exactly five noncredential fields and lost response replay preserves bytes'(){
        given:def input=command();def result=new CodeIntent.Snapshot(UUID.randomUUID(),'code1',Instant.parse(input.issued_at),Instant.parse(input.expires_at))
        when:def first=post(JsonOutput.toJson(input));def retry=post(JsonOutput.toJson(input))
        then:
        2 * store.inRequest('provider',_) >> {String actor,java.util.function.Function operation->operation.apply(scoped)}
        2 * scoped.stage({it.commandId().toString()==input.command_id}) >> result
        first.statusCode()==200;retry.statusCode()==200;first.body()==retry.body()
        new JsonSlurper().parseText(first.body()).keySet()==['intent_id','code_key_id','state','issued_at','expires_at'] as Set
        new JsonSlurper().parseText(first.body()).state=='STAGED'
        !first.body().contains('proof_id');!first.body().contains('eligible');!first.body().contains('managed-subject')
        records.empty
    }
    def 'duplicate directional headers deny before parsing or admission'(){
        when:
        def request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:${app.port()}/internal/sso-code-intents"))
            .timeout(Duration.ofSeconds(6)).header('Content-Type','application/json')
            .header('X-ArMoney-Login-Proof-Write-Key',KEY).header('X-ArMoney-Login-Proof-Write-Key',KEY)
            .POST(HttpRequest.BodyPublishers.ofString('not-json')).build()
        def response=client.send(request,HttpResponse.BodyHandlers.ofString())
        then:response.statusCode()==401;0 * store._
    }
    def 'query parameters are admitted but cannot replace the frozen request body'(){
        when:def response=post(JsonOutput.toJson(command()),KEY,'/internal/sso-code-intents','?subject=foreign')
        then:
        1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function operation->operation.apply(scoped)}
        0 * scoped.stage(_)
        response.statusCode()==400
    }
    def 'reuse cannot manufacture an interactive event or stage a new intent'(){
        when:def response=post(JsonOutput.toJson(command()+[kind:'reuse',event_id:null,password_authenticated_at:null]))
        then:
        1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function operation->operation.apply(scoped)}
        0 * scoped.stage(_)
        response.statusCode()==409
    }
    def 'scope rejection precedes body parsing and no route failure becomes STAGED'(){
        when:def response=post('not-json')
        then:
        1 * store.inRequest('provider',_) >> {throw new Failure(error)}
        0 * scoped.stage(_)
        response.statusCode()==status
        where:
        error | status
        Error.RATE_LIMITED | 429
        Error.UNAVAILABLE | 503
    }
    def 'storage denial expiry and missing original key never become authority'(){
        when:def response=post(JsonOutput.toJson(command()))
        then:
        1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function operation->operation.apply(scoped)}
        1 * scoped.stage(_) >> {throw new Failure(error)}
        response.statusCode()==status
        where:
        error | status
        Error.CONFLICT | 409
        Error.EXPIRED | 410
        Error.UNAVAILABLE | 503
    }
    def 'confirmation and classification handlers do not exist in this stage slice'(){
        when:def response=post('{}',KEY,path)
        then:response.statusCode()==404;0 * store._
        where:path << ['/internal/sso-code-intents/confirm','/internal/sso-code-intents/classification']
    }
}
