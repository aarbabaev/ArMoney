package com.arman.bank.authservice

import com.arman.bank.authservice.application.*
import com.arman.bank.authservice.domain.CodeConfirmation as C
import com.arman.bank.authservice.domain.LoginProof as L
import com.arman.bank.authservice.infrastructure.*
import io.javalin.Javalin
import groovy.json.*
import spock.lang.Specification
import java.net.http.*
import java.time.*

/** Mock owning port and explicitly synthetic observation: transport contract only. */
class CodeConfirmationRoutesSpec extends Specification {
    static final String KEY='synthetic-confirm-directional-credential'
    CodeConfirmationStore store=Mock()
    CodeConfirmationStore.Request scoped=Mock()
    CodeConfirmationStore.Admission admission=Stub()
    CodeConfirmationObservations observations=Mock()
    def service=new CodeConfirmationService(store,observations)
    Javalin app
    HttpClient client=HttpClient.newHttpClient()
    static Map input(){[issuer:CodeIntentKeyLookupRoutesSpec.ISSUER,subject:'subject',client_id:'armoney-web',user_session_id:'session',code_key_id:'old',code_digest:'a'*64]}
    def setup(){
        admission.binding() >> new L.Binding(CodeIntentKeyLookupRoutesSpec.ISSUER,'subject','armoney-web','session')
        admission.startedAt() >> Instant.parse('2026-10-17T00:00:00Z')
        app=Javalin.create{new CodeConfirmationRoutes(service,CodeIntentKeyLookupRoutesSpec.policy(),CodeIntentKeyLookupRoutesSpec.scopes(),'provider',KEY).configure(it)}.start('127.0.0.1',0)
    }
    def cleanup(){app?.stop();client?.close()}
    def post(String body,String key=KEY,boolean duplicate=false){
        def request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:${app.port()}/internal/sso-code-intents/confirm")).timeout(Duration.ofSeconds(6)).header('Content-Type','application/json')
        if(key!=null)request.header('X-ArMoney-Login-Proof-Confirm-Key',key)
        if(duplicate)request.header('X-ArMoney-Login-Proof-Confirm-Key',key)
        client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString())
    }
    def 'directional authentication rejects before scope parsing or observation'(){
        when:def response=post('not-json',key,duplicate)
        then:response.statusCode()==401;0 * store._;0 * observations._
        where:key | duplicate
        null | false
        CodeIntentKeyLookupRoutesSpec.KEY | false
        KEY | true
    }
    def 'unchanged strict six-field schema cannot carry browser identity event or observation'(){
        when:def response=post(body)
        then:1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function action->action.apply(scoped)}
        0 * scoped._;0 * observations._;response.statusCode()==400
        where:body << ['{}','{"issuer":"a","issuer":"b"}',JsonOutput.toJson(input())+' {}','x'*12289,
            JsonOutput.toJson(input()+[event_id:UUID.randomUUID().toString()]),JsonOutput.toJson(input()+[subject:null]),
            JsonOutput.toJson(input()+[user_session_id:'bad session'])]
    }
    def 'direct response is exact original six-field snapshot through one owning scope'(){
        given:def issued=Instant.parse('2026-10-17T00:00:00Z');def snapshot=new L.Snapshot(UUID.randomUUID(),'old',UUID.randomUUID(),L.Kind.INTERACTIVE,true,issued,issued.plusSeconds(120))
        when:def response=post(JsonOutput.toJson(input()))
        then:
        1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function action->action.apply(scoped)}
        1 * scoped.admit(_) >> admission
        1 * observations.observe(_,_) >> null
        1 * scoped.complete(admission,null) >> C.Outcome.confirmed(snapshot)
        response.statusCode()==200
        new JsonSlurper().parseText(response.body()).keySet()==['proof_id','event_id','kind','login_notice_eligible','issued_at','expires_at'] as Set
        !response.body().contains('state');!response.body().contains('code_key_id');!response.body().contains('subject')
    }
    def 'committed denial becomes generic409 only after owning scope returned'(){
        given:def returned=new java.util.concurrent.atomic.AtomicBoolean()
        when:def response=post(JsonOutput.toJson(input()))
        then:
        1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function action->def outcome=action.apply(scoped);returned.set(true);outcome}
        1 * scoped.admit(_) >> admission
        1 * observations.observe(_,_) >> null
        1 * scoped.complete(admission,null) >> C.Outcome.denied()
        returned.get();response.statusCode()==409;response.body()=='{"error":"code_intent_request_rejected"}'
    }
    def 'crossed pair is denied without admission anchor or observation'(){
        when:def response=post(JsonOutput.toJson(input()+[client_id:'armoney-ios']))
        then:1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function action->action.apply(scoped)}
        0 * scoped._;0 * observations._;response.statusCode()==409
    }
    def 'scope rejection precedes malformed body parsing and observation'(){
        when:def response=post('not-json')
        then:1 * store.inRequest('provider',_) >> {throw new L.Failure(error)}
        0 * scoped._;0 * observations._;response.statusCode()==status
        where:error | status
        L.Error.RATE_LIMITED | 429
        L.Error.UNAVAILABLE | 503
    }
    def 'core errors remain exact unavailable absent expired and capacity failures'(){
        when:def response=post(JsonOutput.toJson(input()))
        then:1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function action->action.apply(scoped)}
        1 * scoped.admit(_) >> {throw new L.Failure(error)}
        0 * observations._;0 * scoped.complete(_,_);response.statusCode()==status
        where:error | status
        L.Error.MISSING | 404
        L.Error.EXPIRED | 410
        L.Error.UNAVAILABLE | 503
        L.Error.RATE_LIMITED | 429
    }
    def 'application request capability cannot escape or be reused to open another confirmation'(){
        given:def escaped=null
        when:service.inRequest('provider',{request->escaped=request;true});escaped.confirm(new L.Resolve('a'*64,'old',CodeIntentKeyLookupRoutesSpec.ISSUER,'subject','armoney-web','session'))
        then:1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function action->action.apply(scoped)}
        thrown(L.Failure);0 * scoped._;0 * observations._
    }
}
