package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.domain.CodeConfirmation
import com.arman.bank.authservice.domain.LoginProof as L
import com.sun.net.httpserver.HttpServer
import groovy.json.*
import spock.lang.Specification
import java.net.http.*
import java.time.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Synthetic fixed-mount HTTP peer only; no actual Keycloak propagation/DNS/clock equivalence proof. */
@spock.lang.Isolated('Temporarily replaces JVM-global ProxySelector; always restored in finally')
class HttpCodeSessionObservationsSpec extends Specification {
    static final String ISSUER='https://issuer.example/realm'
    static final String KEY='synthetic-code-session-observation-credential'
    static final String PATH='/sso/realms/armoney/armoney-status/code-session'
    static final L.Binding BINDING=new L.Binding(ISSUER,'subject','armoney-web','session')
    HttpServer server
    ExecutorService executor=Executors.newCachedThreadPool()
    HttpCodeSessionObservations adapter
    AtomicReference<Map> payload=new AtomicReference<>(facts())
    AtomicReference<byte[]> raw=new AtomicReference<>()
    AtomicReference<String> type=new AtomicReference<>('application/json')
    AtomicInteger status=new AtomicInteger(200)
    AtomicInteger calls=new AtomicInteger()
    AtomicLong delay=new AtomicLong()
    AtomicReference<Map> request=new AtomicReference<>()
    CountDownLatch entered=new CountDownLatch(1)
    static Map facts(){[issuer:ISSUER,subject:'subject',client_id:'armoney-web',user_session_id:'session',username:'+971501234567',email:'fixture@example.test',phone_number:'+971501234567',
        enabled:true,user_session_active:true,client_session_active:true,observed_at:'2030-01-01T00:00:00Z',valid_until:'2030-01-01T00:00:05Z']}
    static CodeIntentPairScopes scopes(){new CodeIntentPairScopes('[{"issuer":"'+ISSUER+'","client_id":"armoney-web"}]','[]')}
    def setup(){
        server=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0);server.executor=executor
        server.createContext('/',{exchange->
            calls.incrementAndGet();request.set([path:exchange.requestURI.path,method:exchange.requestMethod,key:exchange.requestHeaders.getFirst('X-ArMoney-Auth-Status-Key'),body:new JsonSlurper().parse(exchange.requestBody)])
            entered.countDown()
            try{
                if(delay.get()>0)Thread.sleep(delay.get())
                byte[] bytes=raw.get()?:JsonOutput.toJson(payload.get()).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                if(type.get()!=null)exchange.responseHeaders.set('Content-Type',type.get())
                if(status.get()==302)exchange.responseHeaders.set('Location','/unexpected-fallback')
                exchange.sendResponseHeaders(status.get(),status.get()==204?-1:bytes.length)
                if(status.get()!=204)exchange.responseBody.write(bytes)
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt()}
            catch(IOException cancelled){}
            finally{exchange.close()}
        } as com.sun.net.httpserver.HttpHandler)
        server.start()
        adapter=new HttpCodeSessionObservations(URI.create('http://127.0.0.1:'+server.address.port),KEY,scopes(),HttpCodeSessionObservations.privateClient())
    }
    def cleanup(){adapter?.close();server?.stop(0);executor.shutdownNow()}
    def 'production client explicitly bypasses hostile JVM default proxy for private observation'(){
        given:
        def original=ProxySelector.getDefault();def intercepted=new AtomicInteger()
        def hostile=new ProxySelector(){
            @Override List<Proxy> select(URI ignored){intercepted.incrementAndGet();[new Proxy(Proxy.Type.HTTP,new InetSocketAddress('127.0.0.1',9))]}
            @Override void connectFailed(URI ignored,SocketAddress address,IOException failed){}
        }
        def result=null
        when:
        try{
            ProxySelector.setDefault(hostile)
            adapter.close()
            def productionClient=HttpCodeSessionObservations.privateClient()
            adapter=new HttpCodeSessionObservations(URI.create('http://127.0.0.1:'+server.address.port),KEY,scopes(),productionClient)
            assert productionClient.proxy().orElseThrow().select(URI.create('http://127.0.0.1:'+server.address.port)).every{it.type()==Proxy.Type.DIRECT}
            result=adapter.observe(BINDING,Instant.now())
        }finally{ProxySelector.setDefault(original)}
        then:result.binding()==BINDING;result.enabled();calls.get()==1;request.get().path==PATH;intercepted.get()==0
        ProxySelector.getDefault()==original
    }
    def 'default false creates no observer resource and does not contact a peer'(){
        expect:HttpCodeSessionObservations.open(new CodeConfirmationConfiguration([:])).isEmpty();calls.get()==0
    }
    def 'fixed endpoint and credential send exactly four fields and decode twelve facts without absolute clock comparison'(){
        when:def result=adapter.observe(BINDING,Instant.parse('2026-10-17T00:00:00Z'))
        then:result.binding()==BINDING;result.enabled();result.userSessionActive();result.clientSessionActive()
        request.get().path==PATH;request.get().method=='POST';request.get().key==KEY
        request.get().body==[issuer:ISSUER,subject:'subject',client_id:'armoney-web',user_session_id:'session'];calls.get()==1
        !adapter.toString().contains(KEY);!result.toString().contains('fixture@example.test')
    }
    def 'literal current negative #flag remains typed facts for owning core only'(){
        given:payload.set(facts()+[(flag):false])
        when:def result=adapter.observe(BINDING,Instant.now())
        then:!([result.enabled(),result.userSessionActive(),result.clientSessionActive()].every{it})
        where:flag << ['enabled','user_session_active','client_session_active']
    }
    def 'nullable contacts remain explicit null without coercion or invented positive values'(){
        given:payload.set(facts()+[email:null,phone_number:null,enabled:false])
        when:def result=adapter.observe(BINDING,Instant.now())
        then:result.email()==null;result.phone()==null;!result.enabled()
    }
    def 'EVERY non200 is unavailable even with an exact negative response body'(){
        given:status.set(code);payload.set(facts()+[enabled:false])
        when:adapter.observe(BINDING,Instant.now())
        then:def unavailable=thrown(L.Failure);unavailable.error()==L.Error.UNAVAILABLE;calls.get()==1
        where:code << [204,302,401,403,404,429,500,503]
    }
    def 'malformed foreign old or unusable relative evidence never becomes a negative fact'(){
        given:payload.set(body)
        when:adapter.observe(BINDING,Instant.now())
        then:def invalid=thrown(L.Failure);invalid.error()==L.Error.UNAVAILABLE;invalid.cause==null
        where:body << [facts()+[subject:'foreign',enabled:false],facts()+[user_session_id:'foreign'],facts()+[enabled:'false'],facts()+[user_session_active:null],
            facts().findAll{k,v->k!='observed_at'},facts()+[extra:true],facts()+[email:'UPPER@example.test'],facts()+[phone_number:'123'],facts()+[username:'bad name'],
            facts()+[username:'bad\u00a0name'],facts()+[username:'\ud800'],facts()+[observed_at:'2030-01-01T00:00:00.000Z'],facts()+[valid_until:'2030-01-01T00:00:00Z'],facts()+[valid_until:'2030-01-01T00:00:01Z'],facts()+[valid_until:'2030-01-01T00:00:06Z']]
    }
    def 'duplicate JSON trailing bytes oversize and invalid UTF8 are unavailable'(){
        given:raw.set(body)
        when:adapter.observe(BINDING,Instant.now())
        then:def invalid=thrown(L.Failure);invalid.error()==L.Error.UNAVAILABLE
        where:body << [(JsonOutput.toJson(facts()).replace('"enabled":true','"enabled":true,"enabled":false')).bytes,
            (JsonOutput.toJson(facts())+' {}').bytes,('x'*12289).bytes,([0xc3,0x28] as byte[])]
    }
    def 'missing or incorrect content type is unavailable'(){
        given:type.set(value)
        when:adapter.observe(BINDING,Instant.now())
        then:def invalid=thrown(L.Failure);invalid.error()==L.Error.UNAVAILABLE
        where:value << [null,'text/plain','application/json; charset=latin1']
    }
    def 'four-second monotonic exchange budget rejects delayed negative without retry'(){
        given:delay.set(4500);payload.set(facts()+[enabled:false]);long started=System.nanoTime()
        when:adapter.observe(BINDING,Instant.now())
        then:def late=thrown(L.Failure);late.error()==L.Error.UNAVAILABLE;calls.get()==1
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<5500
    }
    def 'close cancels in-flight owned exchange and prevents further fanout'(){
        given:delay.set(5000);def pool=Executors.newSingleThreadExecutor()
        def pending=pool.submit({try{adapter.observe(BINDING,Instant.now());null}catch(L.Failure denied){denied.error()}} as Callable)
        assert entered.await(3,TimeUnit.SECONDS)
        when:adapter.close();def result=pending.get(2,TimeUnit.SECONDS)
        then:result==L.Error.UNAVAILABLE;calls.get()==1
        when:adapter.observe(BINDING,Instant.now())
        then:thrown(L.Failure);calls.get()==1
        cleanup:pool.shutdownNow()
    }
    def 'pre-existing interruption is preserved without network fanout'(){
        when:
        Thread.currentThread().interrupt()
        try{adapter.observe(BINDING,Instant.now())}finally{assert Thread.currentThread().isInterrupted();Thread.interrupted()}
        then:def interrupted=thrown(L.Failure);interrupted.error()==L.Error.UNAVAILABLE;calls.get()==0
    }
}
