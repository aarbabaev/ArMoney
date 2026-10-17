package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.CodeConfirmationConfigurationSpec
import com.arman.bank.authservice.CodeIntentConfigurationSpec
import com.arman.bank.authservice.CodeIntentRoutesSpec
import com.arman.bank.authservice.application.CodeIntentStore
import com.arman.bank.authservice.domain.CodeIntent
import com.arman.bank.authservice.domain.LoginProof
import groovy.json.JsonOutput
import io.javalin.Javalin
import spock.lang.Specification
import java.net.http.*
import java.time.*

class CodeConfirmationResourcesSpec extends Specification {
    CodeIntentStore store=Mock()
    CodeIntentStore.Request scoped=Mock()
    Javalin app
    HttpClient client=HttpClient.newHttpClient()
    def cleanup(){app?.stop();client?.close()}
    def 'unselected C2 returns before touching borrowed database or allocating owned resources'(){
        expect:CodeConfirmationResources.open(null,new CodeConfirmationConfiguration([:])).isEmpty()
    }
    def 'enabled construction requires supplied database before allocating an observer'(){
        when:CodeConfirmationResources.open(null,new CodeConfirmationConfiguration(CodeConfirmationConfigurationSpec.environment()))
        then:thrown(NullPointerException)
    }
    def 'disabled standalone configuration does not expose unvalidated composition components'(){
        given:def config=new CodeIntentConfiguration([:])
        when:config.policy()
        then:thrown(IllegalArgumentException)
        when:config.retentionKeys()
        then:thrown(IllegalArgumentException)
        when:config.detailCrypto()
        then:thrown(IllegalArgumentException)
        when:config.stageCredential()
        then:thrown(IllegalArgumentException)
    }
    def post(Map input){
        client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:${app.port()}/internal/sso-code-intents"))
            .timeout(Duration.ofSeconds(6)).header('Content-Type','application/json').header('X-ArMoney-Login-Proof-Write-Key',CodeIntentRoutesSpec.KEY)
            .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(input))).build(),HttpResponse.BodyHandlers.ofString())
    }
    def 'composed exact pair guard rejects crossed projection inside original single admission scope'(){
        given:
        def issuer=CodeIntentRoutesSpec.ISSUER
        def policy=LoginProof.Policy.defaults([issuer,'https://second.example/realm'] as Set,['armoney-web','armoney-ios'] as Set,['code1'] as Set,['provider'] as Set)
        def scopes=new CodeIntentPairScopes('[{"issuer":"'+issuer+'","client_id":"armoney-web"},{"issuer":"https://second.example/realm","client_id":"armoney-ios"}]','[]')
        app=Javalin.create{new CodeIntentRoutes(store,policy,'provider',CodeIntentRoutesSpec.KEY,scopes).configure(it)}.start('127.0.0.1',0)
        when:def response=post(CodeIntentRoutesSpec.command()+[client_id:'armoney-ios'])
        then:1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function action->action.apply(scoped)}
        0 * scoped.stage(_);response.statusCode()==409
    }
    def 'original standalone constructor keeps its existing independently allowed client behavior'(){
        given:
        def issuer=CodeIntentRoutesSpec.ISSUER;def policy=LoginProof.Policy.defaults([issuer] as Set,['armoney-web','armoney-ios'] as Set,['code1'] as Set,['provider'] as Set)
        def input=CodeIntentRoutesSpec.command()+[client_id:'armoney-ios']
        app=Javalin.create{new CodeIntentRoutes(store,policy,'provider',CodeIntentRoutesSpec.KEY).configure(it)}.start('127.0.0.1',0)
        when:def response=post(input)
        then:1 * store.inRequest('provider',_) >> {String actor,java.util.function.Function action->action.apply(scoped)}
        1 * scoped.stage(_) >> new CodeIntent.Snapshot(UUID.randomUUID(),'code1',Instant.parse(input.issued_at),Instant.parse(input.expires_at))
        response.statusCode()==200
    }
}
