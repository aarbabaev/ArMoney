package com.arman.bank.userservice

import com.arman.bank.userservice.application.*
import com.arman.bank.userservice.infrastructure.*
import com.arman.bank.runtime.InternalHttp
import com.arman.bank.runtime.ServiceRuntime
import java.net.http.*
import java.time.Duration
import spock.lang.Specification

/** Synthetic private HTTP admission only; no database or positive contact proof. */
class ProfileDedicatedRouteGuardSpec extends Specification {
    static final String KEY='ordinary-service-key-0000000000000000'
    static InternalHttp.DedicatedRoute route(String method='POST',String path=LiveUserCurrentRoutes.PATH){new InternalHttp.DedicatedRoute(method,path)}
    static HttpRequest request(int port,String method,String path,Map headers=[:]) {
        def b=HttpRequest.newBuilder(URI.create('http://127.0.0.1:'+port+path)).timeout(Duration.ofSeconds(5))
        headers.each{k,v->b.header(k,v)}
        b.method(method,method=='POST'?HttpRequest.BodyPublishers.ofString('{invalid'):HttpRequest.BodyPublishers.noBody()).build()
    }
    def 'rejects duplicate and unrecognized dedicated selections before configuration'(){
        given: def routes=new ProfileRoutes(new ProfileService(Stub(ProfileStore)),KEY)
        when: routes.configure(null,selection)
        then: thrown(IllegalArgumentException)
        where:
        selection << [[route(),route()],[route('GET')],[route('POST','/v1/users/me')],
            [route('POST',LiveUserCurrentRoutes.PATH+'/extra')],null]
    }
    def 'selected current reaches real purpose authentication before malformed body or store'(){
        given:
        def store=Mock(LiveUserCurrentStore)
        def profile=new ProfileRoutes(new ProfileService(Stub(ProfileStore)),KEY)
        def current=new LiveUserCurrentRoutes(store,LiveUserRevisionConfigurationSpec.enabled())
        def runtime=ServiceRuntime.start('user-service',0,null,{ c -> profile.configure(c,[route()]);current.configure(c) })
        def client=HttpClient.newHttpClient()
        when: def result=client.send(request(runtime.port(),'POST',LiveUserCurrentRoutes.PATH,headers),HttpResponse.BodyHandlers.ofString())
        then:
        result.statusCode()==status
        result.headers().firstValue('Cache-Control').orElse('')=='no-store'
        0 * store._
        cleanup:client?.close();runtime?.close();current?.close()
        where:
        headers | status
        [:] | 401
        [(LiveUserCurrentRoutes.HEADER):'wrong'] | 401
        [(LiveUserCurrentRoutes.HEADER):LiveUserRevisionConfigurationSpec.CURRENT,'Content-Type':'application/json'] | 400
    }
    def 'wrong method encoded path prefixes and ordinary routes retain service key requirement'(){
        given:
        def runtime=ServiceRuntime.start('user-service',0,null,{ c -> new ProfileRoutes(new ProfileService(Stub(ProfileStore)),KEY).configure(c,[route()]) })
        def client=HttpClient.newHttpClient()
        when: def result=client.send(request(runtime.port(),method,path,[(LiveUserCurrentRoutes.HEADER):LiveUserRevisionConfigurationSpec.CURRENT]),HttpResponse.BodyHandlers.ofString())
        then: result.statusCode()==401
        cleanup:client?.close();runtime?.close()
        where:
        method | path
        'GET' | LiveUserCurrentRoutes.PATH
        'POST' | LiveUserCurrentRoutes.PATH+'/extra'
        'POST' | LiveUserCurrentRoutes.PATH+'-extra'
        'POST' | LiveUserCurrentRoutes.PATH.replace('user-current','%75ser-current')
        'GET' | '/v1/users/me'
    }
    def 'ordinary valid service key still requires owner and old configure never exempts current'(){
        given:
        def store=Mock(LiveUserCurrentStore)
        def current=new LiveUserCurrentRoutes(store,LiveUserRevisionConfigurationSpec.enabled())
        def runtime=ServiceRuntime.start('user-service',0,null,{ c -> new ProfileRoutes(new ProfileService(Stub(ProfileStore)),KEY).configure(c);current.configure(c) })
        def client=HttpClient.newHttpClient()
        when:
        def ordinary=client.send(request(runtime.port(),'GET','/v1/users/me',['X-Service-Key':KEY]),HttpResponse.BodyHandlers.ofString())
        def purpose=client.send(request(runtime.port(),'POST',LiveUserCurrentRoutes.PATH,[(LiveUserCurrentRoutes.HEADER):LiveUserRevisionConfigurationSpec.CURRENT]),HttpResponse.BodyHandlers.ofString())
        then:
        ordinary.statusCode()==401
        purpose.statusCode()==401
        0 * store._
        cleanup:client?.close();runtime?.close();current?.close()
    }
}
