package com.arman.bank.userservice
import com.arman.bank.userservice.application.LiveUserCurrentStore
import com.arman.bank.userservice.domain.LiveUserRevision
import com.arman.bank.userservice.infrastructure.*
import com.sun.net.httpserver.HttpServer
import spock.lang.Specification
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
class HttpUserMutationSuspensionSpec extends Specification {
    def 'authenticated exact synthetic response matches original request and unknown source denies'(){
        given:
        def server=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        boolean wrong=false
        int calls=0
        server.createContext(HttpUserMutationSuspension.PATH){exchange->
            try{
                assert exchange.requestHeaders.getFirst(HttpUserMutationSuspension.HEADER)==LiveUserRevisionConfigurationSpec.MUTATION
                def parsedRequest=LiveUserCurrentRoutes.parse(exchange.requestBody.readNBytes(4097));calls++
                def response=LiveUserRevisionSpec.negativeClassification(parsedRequest)
                if(wrong)response.outcome='UNKNOWN'
                byte[] body=LiveUserCurrentRoutes.encode(response)
                exchange.responseHeaders.set('Content-Type','application/json');exchange.sendResponseHeaders(200,body.length);exchange.responseBody.write(body)
            }finally{exchange.close()}
        }
        server.start()
        def client=new HttpUserMutationSuspension(LiveUserRevisionConfigurationSpec.enabled(URI.create('http://127.0.0.1:'+server.address.port)))
        def c=LiveUserRevisionSpec.command()
        def request=LiveUserRevision.suspension(LiveUserRevisionSpec.WRITER,c.operationId(),c.identityId(),null,null,LiveUserRevision.projection(c))
        expect:client.suspend(LiveUserRevisionSpec.WRITER,request,System.nanoTime()).outcome=='NOT_MIG'
        when:wrong=true;client.suspend(LiveUserRevisionSpec.WRITER,request,System.nanoTime())
        then:thrown(LiveUserCurrentStore.Rejected)
        when:client.suspend(LiveUserRevisionSpec.WRITER,request,System.nanoTime()-4_000_000_000L)
        then:thrown(LiveUserCurrentStore.Rejected)
        calls==2
        cleanup:client?.close();server?.stop(0)
    }
    def 'sixteen hostile peer sockets observe cancellation and all client permits recover #mode'(){
        given:
        def listener=new ServerSocket(0,32,InetAddress.getByName('127.0.0.1'))
        listener.soTimeout=10000
        def serverPool=Executors.newFixedThreadPool(33),callers=Executors.newFixedThreadPool(16)
        def drip=Executors.newSingleThreadScheduledExecutor()
        def peers=new CopyOnWriteArrayList<Socket>(),badPeers=new CopyOnWriteArrayList<Socket>()
        def handlers=new CopyOnWriteArrayList<Future>()
        def cancelled=new CountDownLatch(16),served=new AtomicInteger()
        def accept=serverPool.submit({->
            (0..<32).each{index->
                def socket=listener.accept();socket.soTimeout=7000;peers.add(socket)
                handlers.add(serverPool.submit({->
                    try{
                        def head=header(socket.inputStream)
                        int length=Integer.parseInt(head.split('\r\n').find{it.toLowerCase(Locale.ROOT).startsWith('content-length:')}.split(':',2)[1].trim())
                        if(length>4096)throw new IllegalStateException()
                        byte[] body=socket.inputStream.readNBytes(length)
                        if(index<16){
                            badPeers.add(socket)
                            socket.outputStream.write('HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100000\r\nConnection: keep-alive\r\n\r\n'.bytes)
                            if(mode=='OVERSIZE')socket.outputStream.write((' '*4097).bytes)
                            socket.outputStream.flush()
                            boolean closed=false
                            try{closed=socket.inputStream.read()==-1}catch(SocketTimeoutException ignored){}catch(SocketException ignored){closed=true}
                            if(closed)cancelled.countDown()
                            return closed
                        }
                        def parsedRequest=LiveUserCurrentRoutes.parse(body)
                        byte[] response=LiveUserCurrentRoutes.encode(LiveUserRevisionSpec.negativeClassification(parsedRequest))
                        socket.outputStream.write(('HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: '+response.length+'\r\nConnection: close\r\n\r\n').bytes)
                        socket.outputStream.write(response);socket.outputStream.flush();served.incrementAndGet();true
                    }finally{socket.close()}
                } as Callable<Boolean>))
            }
        } as Callable)
        if(mode=='DRIP')drip.scheduleAtFixedRate({->badPeers.each{socket->try{socket.outputStream.write(32);socket.outputStream.flush()}catch(IOException ignored){}}} as Runnable,0,100,TimeUnit.MILLISECONDS)
        def client=new HttpUserMutationSuspension(LiveUserRevisionConfigurationSpec.enabled(URI.create('http://127.0.0.1:'+listener.localPort)))
        def c=LiveUserRevisionSpec.command();def request=LiveUserRevision.suspension(LiveUserRevisionSpec.WRITER,c.operationId(),c.identityId(),null,null,LiveUserRevision.projection(c))
        when:def failures=(1..16).collect{callers.submit({->try{client.suspend(LiveUserRevisionSpec.WRITER,request,System.nanoTime());false}catch(LiveUserCurrentStore.Rejected ignored){true}} as Callable<Boolean>)}.collect{it.get(7,TimeUnit.SECONDS)}
        then:failures.every{it}
        cancelled.await(3,TimeUnit.SECONDS) // Peer-observed EOF/reset BEFORE test cleanup closes anything.
        when:def recovered=(1..16).collect{callers.submit({->client.suspend(LiveUserRevisionSpec.WRITER,request,System.nanoTime()).outcome} as Callable<String>)}.collect{it.get(6,TimeUnit.SECONDS)}
        then:recovered.every{it=='NOT_MIG'} && served.get()==16
        accept.get(2,TimeUnit.SECONDS)!=null
        handlers.every{it.get(2,TimeUnit.SECONDS)==true}
        cleanup:client?.close();listener?.close();peers?.each{it.close()};drip?.shutdownNow();callers?.shutdownNow();serverPool?.shutdownNow();assert callers==null||callers.awaitTermination(3,TimeUnit.SECONDS);assert serverPool==null||serverPool.awaitTermination(3,TimeUnit.SECONDS);assert drip==null||drip.awaitTermination(3,TimeUnit.SECONDS)
        where:mode << ['STALL','DRIP','OVERSIZE']
    }
    private static String header(InputStream stream){
        def bytes=new ByteArrayOutputStream();int n
        while((n=stream.read())!=-1){bytes.write(n);if(bytes.size()>8192)throw new IllegalStateException();String text=bytes.toString('US-ASCII');if(text.endsWith('\r\n\r\n'))return text}
        throw new EOFException()
    }
}
