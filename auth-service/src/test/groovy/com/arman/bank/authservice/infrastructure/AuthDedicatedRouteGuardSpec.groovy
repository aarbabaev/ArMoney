package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.LegacyMigrationFinalizationStore
import com.arman.bank.runtime.InternalHttp
import com.arman.bank.runtime.ServiceRuntime
import java.net.http.*
import java.time.Duration
import spock.lang.Specification

/** Synthetic private HTTP admission only; no database or migration success. */
class AuthDedicatedRouteGuardSpec extends Specification {
    static final String KEY = 'ordinary-service-key-0000000000000000'
    static final String PURPOSE = 'decision-purpose-key-0000000000000000'
    static final String PATH = LegacyMigrationFinalizationRoutes.PREFIX + 'decision'
    static InternalHttp.DedicatedRoute route(String method='POST',String path=PATH) { new InternalHttp.DedicatedRoute(method,path) }
    static HttpRequest request(int port,String method,String path,Map headers=[:]) {
        def b=HttpRequest.newBuilder(URI.create('http://127.0.0.1:'+port+path)).timeout(Duration.ofSeconds(5))
        headers.each{k,v->b.header(k,v)}
        b.method(method,method=='POST'?HttpRequest.BodyPublishers.ofString('{invalid'):HttpRequest.BodyPublishers.noBody()).build()
    }
    def 'rejects duplicate or unrecognized selections before touching configuration'() {
        when: new AuthRoutes(null,KEY).configure(null,selection)
        then: thrown(IllegalArgumentException)
        where:
        selection << [[route(),route()],[route('GET')],[route('POST','/v1/auth/login')],
            [route('POST',PATH+'/extra')],null]
    }
    def 'only exact selected POST reaches genuine purpose authentication before body or store'() {
        given:
        def store=Mock(LegacyMigrationFinalizationStore)
        def ordinary=new AuthRoutes(null,KEY)
        def purpose=new LegacyMigrationFinalizationRoutes(store,PURPOSE,true)
        def runtime=ServiceRuntime.start('auth-service',0,null,{ config -> ordinary.configure(config,[route()]);purpose.configure(config) })
        def client=HttpClient.newHttpClient()
        when:
        def result=client.send(request(runtime.port(),'POST',PATH,headers),HttpResponse.BodyHandlers.ofString())
        then:
        result.statusCode()==status
        result.headers().firstValue('Cache-Control').orElse('')=='no-store'
        0 * store._
        cleanup:
        client?.close();runtime?.close()
        where:
        headers | status
        [:] | 401
        [(LegacyMigrationFinalizationRoutes.HEADER):'wrong'] | 401
        [(LegacyMigrationFinalizationRoutes.HEADER):PURPOSE,'Content-Type':'application/json'] | 400
    }
    def 'wrong method encoded raw path and prefix variants never inherit exemption'() {
        given:
        def runtime=ServiceRuntime.start('auth-service',0,null,{ c -> new AuthRoutes(null,KEY).configure(c,[route()]) })
        def client=HttpClient.newHttpClient()
        when: def result=client.send(request(runtime.port(),method,path,[(LegacyMigrationFinalizationRoutes.HEADER):PURPOSE]),HttpResponse.BodyHandlers.ofString())
        then: result.statusCode()==401
        cleanup:
        client?.close();runtime?.close()
        where:
        method | path
        'GET' | PATH
        'POST' | PATH+'/extra'
        'POST' | PATH+'-extra'
        'POST' | PATH.replace('decision','%64ecision')
        'POST' | '/v1/auth/login'
    }
    def 'old configure keeps ordinary key required even if purpose route registered'() {
        given:
        def store=Mock(LegacyMigrationFinalizationStore)
        def runtime=ServiceRuntime.start('auth-service',0,null,{ c -> new AuthRoutes(null,KEY).configure(c);new LegacyMigrationFinalizationRoutes(store,PURPOSE,true).configure(c) })
        def client=HttpClient.newHttpClient()
        when: def result=client.send(request(runtime.port(),'POST',PATH,[(LegacyMigrationFinalizationRoutes.HEADER):PURPOSE]),HttpResponse.BodyHandlers.ofString())
        then:
        result.statusCode()==401
        0 * store._
        cleanup: client?.close();runtime?.close()
    }
}
