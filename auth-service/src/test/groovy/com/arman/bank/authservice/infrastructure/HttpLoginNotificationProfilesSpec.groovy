package com.arman.bank.authservice.infrastructure
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import spock.lang.Specification
import java.util.concurrent.*
class HttpLoginNotificationProfilesSpec extends Specification {
    HttpServer server
    ExecutorService threads
    HttpLoginNotificationProfiles client
    UUID owner=UUID.randomUUID()
    Map body
    String raw
    int status=200
    int requests
    def setup(){
        body=[identity_id:owner.toString(),display_name:'  Optional <Owner>  ']
        server=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        threads=Executors.newFixedThreadPool(2);server.executor=threads
        server.createContext('/v1/users/me'){exchange->
            try{
                requests++
                assert exchange.requestMethod=='GET'
                assert exchange.requestHeaders.getFirst('X-Service-Key')=='k'*43
                assert exchange.requestHeaders.getFirst('X-Identity-Id')==this.owner.toString()
                byte[] bytes=(raw?:JsonOutput.toJson(body)).getBytes('UTF-8')
                exchange.responseHeaders.set('Content-Type','application/json')
                exchange.responseHeaders.set('Location','http://127.0.0.1:'+server.address.port+'/redirect')
                exchange.sendResponseHeaders(status,bytes.length);exchange.responseBody.write(bytes)
            }finally{exchange.close()}
        }
        server.createContext('/redirect'){exchange->requests++;exchange.sendResponseHeaders(500,-1);exchange.close()}
        server.start();client=new HttpLoginNotificationProfiles(URI.create('http://127.0.0.1:'+server.address.port),'k'*43)
    }
    def cleanup(){client?.close();server?.stop(0);threads?.shutdownNow();threads?.awaitTermination(3,TimeUnit.SECONDS)}
    def 'existing private profile contract returns only a bounded owner-matched optional label'(){
        expect:client.displayName(owner)=='Optional <Owner>'
        requests==1
    }
    def 'foreign malformed oversized and redirect responses use neutral greeting without retry'(){
        given:
        if(fault=='foreign')body.identity_id=UUID.randomUUID().toString()
        if(fault=='missing')body.remove('identity_id')
        if(fault=='type')body.display_name=7
        if(fault=='control')body.display_name='bad\r\nlabel'
        if(fault=='long')body.display_name='x'*101
        if(fault=='duplicate')raw=JsonOutput.toJson(body).replace('"display_name":','"display_name":"wrong","display_name":')
        if(fault=='oversized')raw='x'*16385
        if(fault=='redirect')status=302
        if(fault=='outage')status=503
        when:def actual=client.displayName(owner)
        then:actual==null;requests==1
        where:fault<<['foreign','missing','type','control','long','duplicate','oversized','redirect','outage']
    }
    def 'closed client never reads a profile and interrupted lookup preserves interruption'(){
        when:client.close();def actual=client.displayName(owner)
        then:actual==null;requests==0
        when:
        client=new HttpLoginNotificationProfiles(URI.create('http://127.0.0.1:'+server.address.port),'k'*43)
        Thread.currentThread().interrupt();client.displayName(owner)
        then:thrown(InterruptedException)
        Thread.currentThread().isInterrupted()
        cleanup:Thread.interrupted()
    }
}
