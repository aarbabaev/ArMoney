package com.arman.bank.userservice
import com.arman.bank.runtime.ServiceRuntime
import com.arman.bank.userservice.application.LiveUserCurrentStore
import com.arman.bank.userservice.infrastructure.*
import java.net.http.*
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
class LiveUserCurrentRoutesSpec extends Specification {
    def 'private current route rejects missing purpose key and malformed wire without invoking store'(){
        given:int calls=0
        def store={writer,request->calls++;throw new IllegalStateException()} as LiveUserCurrentStore
        def routes=new LiveUserCurrentRoutes(store,LiveUserRevisionConfigurationSpec.enabled())
        def runtime=ServiceRuntime.start('user-service',0,null,routes::configure)
        def client=HttpClient.newHttpClient()
        when:
        def unauthorized=client.send(HttpRequest.newBuilder(URI.create('http://127.0.0.1:'+runtime.port()+LiveUserCurrentRoutes.PATH)).timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString('{}')).build(),HttpResponse.BodyHandlers.ofString())
        def malformed=client.send(HttpRequest.newBuilder(URI.create('http://127.0.0.1:'+runtime.port()+LiveUserCurrentRoutes.PATH)).timeout(Duration.ofSeconds(5)).header('Content-Type','application/json').header(LiveUserCurrentRoutes.HEADER,LiveUserRevisionConfigurationSpec.CURRENT).POST(HttpRequest.BodyPublishers.ofString('{"n":1e0}')).build(),HttpResponse.BodyHandlers.ofString())
        then:unauthorized.statusCode()==401 && unauthorized.body()=='{"error":"rejected"}'
        unauthorized.headers().firstValue('Cache-Control').orElse('')=='no-store'
        malformed.statusCode()==400 && calls==0
        cleanup:client?.close();runtime?.close();routes?.close()
    }
    def 'sixteen hostile inbound sockets are physically closed and every route permit recovers #mode'(){
        given:def calls=new AtomicInteger()
        def routes=new LiveUserCurrentRoutes({writer,request->calls.incrementAndGet();throw new LiveUserCurrentStore.Rejected(409)} as LiveUserCurrentStore,LiveUserRevisionConfigurationSpec.enabled())
        def runtime=ServiceRuntime.start('user-service',0,null,routes::configure)
        def sockets=[]
        def readers=Executors.newFixedThreadPool(16)
        def drip=Executors.newSingleThreadScheduledExecutor()
        (1..16).each{
            def socket=new Socket('127.0.0.1',runtime.port());socket.soTimeout=7000;sockets.add(socket)
            String headers='POST '+LiveUserCurrentRoutes.PATH+' HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n'+LiveUserCurrentRoutes.HEADER+': '+LiveUserRevisionConfigurationSpec.CURRENT+'\r\nContent-Length: 100000\r\nConnection: keep-alive\r\n\r\n'
            socket.outputStream.write(headers.bytes)
            if(mode=='OVERSIZE')socket.outputStream.write((' '*4097).bytes)
            socket.outputStream.flush()
        }
        if(mode=='DRIP')drip.scheduleAtFixedRate({->sockets.each{socket->try{socket.outputStream.write(32);socket.outputStream.flush()}catch(IOException ignored){}}} as Runnable,0,100,TimeUnit.MILLISECONDS)
        def closed=sockets.collect{socket->readers.submit({->try{int total=0,n;while((n=socket.inputStream.read())!=-1){if(++total>8192)return false};true}catch(SocketTimeoutException ignored){false}catch(SocketException ignored){true}} as Callable<Boolean>)}
        when:def actualClosure=closed.collect{it.get(8,TimeUnit.SECONDS)}
        then:actualClosure.every{it}
        calls.get()==0
        when:
        def client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        def recovery=(1..16).collect{client.sendAsync(HttpRequest.newBuilder(URI.create('http://127.0.0.1:'+runtime.port()+LiveUserCurrentRoutes.PATH)).timeout(Duration.ofSeconds(5)).header('Content-Type','application/json').header(LiveUserCurrentRoutes.HEADER,LiveUserRevisionConfigurationSpec.CURRENT).POST(HttpRequest.BodyPublishers.ofString('{}')).build(),HttpResponse.BodyHandlers.discarding())}
        def statuses=recovery.collect{it.get(6,TimeUnit.SECONDS).statusCode()}
        then:statuses.every{it==409}
        calls.get()==16
        cleanup:drip?.shutdownNow();sockets?.each{it.close()};readers?.shutdownNow();client?.close();runtime?.close();routes?.close();assert readers==null||readers.awaitTermination(3,TimeUnit.SECONDS);assert drip==null||drip.awaitTermination(3,TimeUnit.SECONDS)
        where:mode << ['STALL','DRIP','OVERSIZE']
    }
}
