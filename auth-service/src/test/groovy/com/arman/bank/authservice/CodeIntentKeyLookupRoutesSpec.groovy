package com.arman.bank.authservice

import com.arman.bank.authservice.application.CodeIntentKeyLookupStore
import com.arman.bank.authservice.domain.CodeIntentKeyLookup as K
import com.arman.bank.authservice.domain.LoginProof
import com.arman.bank.authservice.infrastructure.*
import io.javalin.Javalin
import groovy.json.*
import spock.lang.Specification
import java.net.http.*
import java.time.*

class CodeIntentKeyLookupRoutesSpec extends Specification {
    static final String KEY='synthetic-key-lookup-directional-credential'
    static final String ISSUER='https://issuer.example/realm'
    CodeIntentKeyLookupStore store=Mock()
    CodeIntentKeyLookupStore.Request scoped=Mock()
    Javalin app
    HttpClient client=HttpClient.newHttpClient()
    static def policy(){LoginProof.Policy.defaults([ISSUER,'https://second.example/realm'] as Set,['armoney-web','armoney-ios'] as Set,['old','new'] as Set,['provider'] as Set)}
    static def scopes(){new CodeIntentPairScopes('[{"issuer":"'+ISSUER+'","client_id":"armoney-web"},{"issuer":"https://second.example/realm","client_id":"armoney-ios"}]','[]')}
    static Map input(){[issuer:ISSUER,subject:'subject',client_id:'armoney-web',user_session_id:'session',candidates:[[code_key_id:'old',code_digest:'a'*64]]]}
    def setup(){app=Javalin.create{new CodeIntentKeyLookupRoutes(store,policy(),scopes(),'provider',KEY).configure(it)}.start('127.0.0.1',0)}
    def cleanup(){app?.stop();client?.close()}
    def post(String body,String key=KEY,boolean duplicate=false,String query=''){
        def request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:${app.port()}/internal/sso-code-intents/key${query}")).timeout(Duration.ofSeconds(6)).header('Content-Type','application/json')
        if(key!=null)request.header('X-ArMoney-Code-Intent-Lookup-Key',key)
        if(duplicate)request.header('X-ArMoney-Code-Intent-Lookup-Key',key)
        client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString())
    }
    def 'wrong missing cross-operation or duplicate credentials deny before scope and body'(){
        when:def response=post('not-json',key,duplicate)
        then:response.statusCode()==401;0 * store._
        where:
        key | duplicate
        null | false
        'synthetic-confirm-directional-credential' | false
        KEY | true
    }
    def 'strict five-field lookup rejects malformed and extra candidate fields after exactly one admission'(){
        when:def response=post(body)
        then:
        1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function action->action.apply(scoped)}
        0 * scoped.lookup(_)
        response.statusCode()==400;response.body()=='{"error":"code_intent_request_rejected"}'
        response.headers().firstValue('Cache-Control').get()=='no-store'
        where:body << ['{}','{"issuer":"a","issuer":"b"}',JsonOutput.toJson(input())+' {}','x'*12289,
            JsonOutput.toJson(input()+[actor:'forged']),JsonOutput.toJson(input()+[subject:null]),
            JsonOutput.toJson(input()+[candidates:[[code_key_id:'old',code_digest:'a'*64,extra:true]]]),
            JsonOutput.toJson(input()+[candidates:[[code_key_id:'old',code_digest:'a'*64],[code_key_id:'new',code_digest:'a'*64]]])]
    }
    def 'exact key response contains only offered original key ID and no principal metadata'(){
        when:def response=post(JsonOutput.toJson(input()))
        then:
        1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function action->action.apply(scoped)}
        1 * scoped.lookup(_) >> new K.Result('old')
        response.statusCode()==200;response.body()=='{"code_key_id":"old"}'
        response.headers().firstValue('Content-Type').get().startsWith('application/json')
    }
    def 'unoffered returned key ID is unavailable rather than producer fallback'(){
        when:def response=post(JsonOutput.toJson(input()))
        then:1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function action->action.apply(scoped)}
        1 * scoped.lookup(_) >> new K.Result('new');response.statusCode()==503
    }
    def 'query parameters cannot replace or supplement the frozen lookup object'(){
        when:def response=post(JsonOutput.toJson(input()),KEY,false,'?actor=foreign')
        then:1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function action->action.apply(scoped)}
        0 * scoped.lookup(_);response.statusCode()==400
    }
    def 'crossed issuer client projection cannot authorize lookup'(){
        when:def response=post(JsonOutput.toJson(input()+[client_id:'armoney-ios']))
        then:1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function action->action.apply(scoped)}
        0 * scoped.lookup(_);response.statusCode()==409
    }
    def 'scope saturation precedes JSON parsing and all storage errors remain generic failures'(){
        when:def response=post('not-json')
        then:1 * store.inRequest('provider',_) >> {throw new K.Failure(error)}
        0 * scoped.lookup(_);response.statusCode()==status;response.body()=='{"error":"code_intent_request_rejected"}'
        where:error | status
        K.Error.RATE_LIMITED | 429
        K.Error.UNAVAILABLE | 503
    }
    def 'lookup maps absent binding and unreadable original outcomes without fallback'(){
        when:def response=post(JsonOutput.toJson(input()))
        then:1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function action->action.apply(scoped)}
        1 * scoped.lookup(_) >> {throw new K.Failure(error)}
        response.statusCode()==status
        where:error | status
        K.Error.MISSING | 404
        K.Error.CONFLICT | 409
        K.Error.UNAVAILABLE | 503
    }
}
