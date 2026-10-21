package com.arman.bank.userservice

import com.arman.bank.runtime.ServiceRuntime
import com.arman.bank.userservice.application.VerificationProjectionStore
import com.arman.bank.userservice.domain.VerificationProjection
import com.arman.bank.userservice.infrastructure.*
import java.net.http.*
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import spock.lang.Specification

/** Physical HTTP ingress and original-clock propagation only. Synthetic stores
 * return the existing transport ACK, never an Auth classification or bank grant. */
class VerificationProjectionBodyDeadlineSpec extends Specification {
    static final String KEY='synthetic-projection-body-deadline-0001'
    static VerificationProjectionStore store(AtomicInteger admissions,AtomicInteger commits,Closure clockCheck={long ignored->}) {
        new VerificationProjectionStore(){
            boolean admit(String writer){admissions.incrementAndGet();true}
            int cleanup(){0}
            VerificationProjection.Ack commit(String writer,VerificationProjection c){throw new AssertionError('Original ingress start was not propagated')}
            VerificationProjection.Ack commit(String writer,VerificationProjection c,long originalStartedNanos){clockCheck.call(originalStartedNanos);commits.incrementAndGet();c.ack()}
        }
    }
    static String json(){new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(VerificationProjectionRoutesSpec.body())}
    static String headers(int length,boolean authenticated=true){
        'POST '+VerificationProjectionRoutes.PATH+' HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n'+(authenticated?'X-ArMoney-Verification-Projection-Key: '+KEY+'\r\n':'')+'Content-Length: '+length+'\r\nConnection: close\r\n\r\n'
    }
    def 'authenticated delayed body keeps handle entry clock and admission precedes body read'(){
        given:
        def admissions=new AtomicInteger()
        def commits=new AtomicInteger()
        def seen=new AtomicLong()
        def firstAdmission=new CountDownLatch(1)
        def base=store(admissions,commits,{long start->seen.set(start);assert System.nanoTime()-start>=1_000_000_000L;assert System.nanoTime()-start<4_000_000_000L})
        def observing=new VerificationProjectionStore(){
            boolean admit(String writer){boolean admitted=base.admit(writer);firstAdmission.countDown();admitted}
            int cleanup(){0}
            VerificationProjection.Ack commit(String writer,VerificationProjection c){throw new AssertionError('Clock omitted')}
            VerificationProjection.Ack commit(String writer,VerificationProjection c,long start){base.commit(writer,c,start)}
        }
        def routes=new VerificationProjectionRoutes(observing,new VerificationProjectionConfiguration(true,[auth:KEY]))
        def runtime=ServiceRuntime.start('user-service',0,null,routes::configure)
        def socket=new Socket('127.0.0.1',runtime.port());socket.soTimeout=6000
        byte[] body=json().bytes
        long sent=System.nanoTime()
        socket.outputStream.write(headers(body.length).bytes);socket.outputStream.flush()
        expect:firstAdmission.await(2,TimeUnit.SECONDS)
        admissions.get()==1 && commits.get()==0
        when:Thread.sleep(1250);socket.outputStream.write(body);socket.outputStream.flush()
        String response=new String(socket.inputStream.readAllBytes(),'UTF-8')
        then:response.startsWith('HTTP/1.1 200')
        commits.get()==1 && seen.get()>=sent
        cleanup:socket?.close();runtime?.close()
    }
    def 'missing purpose key rejects before admission or incomplete body read'(){
        given:
        def admissions=new AtomicInteger()
        def commits=new AtomicInteger()
        def routes=new VerificationProjectionRoutes(store(admissions,commits),new VerificationProjectionConfiguration(true,[auth:KEY]))
        def runtime=ServiceRuntime.start('user-service',0,null,routes::configure)
        def socket=new Socket('127.0.0.1',runtime.port());socket.soTimeout=2000
        when:socket.outputStream.write(headers(100000,false).bytes);socket.outputStream.flush()
        def line=new BufferedReader(new InputStreamReader(socket.inputStream,'US-ASCII')).readLine()
        then:line.startsWith('HTTP/1.1 401')
        admissions.get()==0 && commits.get()==0
        cleanup:socket?.close();runtime?.close()
    }
    def 'old twelve KiB route closes every hostile socket and all sixteen permits recover #mode'(){
        given:
        def admissions=new AtomicInteger()
        def commits=new AtomicInteger()
        def routes=new VerificationProjectionRoutes(store(admissions,commits),new VerificationProjectionConfiguration(true,[auth:KEY]))
        def runtime=ServiceRuntime.start('user-service',0,null,routes::configure)
        def sockets=[]
        def readers=Executors.newFixedThreadPool(16)
        def drip=Executors.newSingleThreadScheduledExecutor()
        (1..16).each{
            def socket=new Socket('127.0.0.1',runtime.port());socket.soTimeout=7000;sockets.add(socket)
            socket.outputStream.write(headers(100000).bytes)
            if(mode=='OVERSIZE')socket.outputStream.write((' '*12289).bytes)
            socket.outputStream.flush()
        }
        if(mode=='DRIP')drip.scheduleAtFixedRate({->sockets.each{socket->try{socket.outputStream.write(32);socket.outputStream.flush()}catch(IOException ignored){}}} as Runnable,0,100,TimeUnit.MILLISECONDS)
        def closed=sockets.collect{socket->readers.submit({->try{int n,total=0;while((n=socket.inputStream.read())!=-1){if(++total>8192)return false};true}catch(SocketTimeoutException ignored){false}catch(SocketException ignored){true}} as Callable<Boolean>)}
        when:def closure=closed.collect{it.get(8,TimeUnit.SECONDS)}
        then:closure.every{it}
        admissions.get()==16 && commits.get()==0
        when:
        def client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        def healthy=(1..16).collect{client.sendAsync(HttpRequest.newBuilder(URI.create('http://127.0.0.1:'+runtime.port()+VerificationProjectionRoutes.PATH)).timeout(Duration.ofSeconds(5)).header('Content-Type','application/json').header('X-ArMoney-Verification-Projection-Key',KEY).POST(HttpRequest.BodyPublishers.ofString(json())).build(),HttpResponse.BodyHandlers.discarding())}
        def statuses=healthy.collect{it.get(6,TimeUnit.SECONDS).statusCode()}
        then:statuses.every{it==200}
        admissions.get()==32 && commits.get()==16
        cleanup:drip?.shutdownNow();sockets?.each{it.close()};readers?.shutdownNow();client?.close();runtime?.close();assert readers==null||readers.awaitTermination(3,TimeUnit.SECONDS);assert drip==null||drip.awaitTermination(3,TimeUnit.SECONDS)
        where:mode << ['STALL','DRIP','OVERSIZE']
    }
}
