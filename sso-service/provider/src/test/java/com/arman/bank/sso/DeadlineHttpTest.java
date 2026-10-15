package com.arman.bank.sso;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

final class DeadlineHttpTest {
    @Test void realDelayedBodyIsCancelledAtTotalDeadlineAfterHeadersArrive() throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        var headersSent=new CountDownLatch(1);var releaseBody=new CountDownLatch(1);
        var workers=Executors.newSingleThreadExecutor();server.setExecutor(workers);
        server.createContext("/slow",exchange->{
            try(exchange){exchange.sendResponseHeaders(200,2);exchange.getResponseBody().flush();headersSent.countDown();
                try{releaseBody.await(3,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                try{exchange.getResponseBody().write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(java.io.IOException cancelled){ }
            }
        });server.start();
        try(var client=HttpClient.newHttpClient()){
            var request=HttpRequest.newBuilder(uri(server,"/slow")).timeout(Duration.ofMillis(400)).GET().build();
            long started=System.nanoTime();
            assertThrows(HttpTimeoutException.class,()->DeadlineHttp.send(client,request));
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            assertTrue(headersSent.await(1,TimeUnit.SECONDS),"Must receive headers before rejecting delayed body");
            assertTrue(elapsed<1500,"Total body deadline must not wait for server body release: "+elapsed);
        }finally{releaseBody.countDown();server.stop(0);workers.shutdownNow();assertTrue(workers.awaitTermination(3,TimeUnit.SECONDS));}
    }
    @Test void realNormalAndOversizedBodiesAreBounded() throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/normal",exchange->{try(exchange){byte[] bytes="{}".getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}});
        server.createContext("/large",exchange->{try(exchange){byte[] bytes=new byte[12289];exchange.sendResponseHeaders(200,bytes.length);try{exchange.getResponseBody().write(bytes);}catch(java.io.IOException cancelled){}}});
        server.start();try(var client=HttpClient.newHttpClient()){
            assertArrayEquals("{}".getBytes(),DeadlineHttp.send(client,HttpRequest.newBuilder(uri(server,"/normal")).timeout(Duration.ofSeconds(2)).GET().build()).body());
            assertThrows(java.io.IOException.class,()->DeadlineHttp.send(client,HttpRequest.newBuilder(uri(server,"/large")).timeout(Duration.ofSeconds(2)).GET().build()));
        }finally{server.stop(0);}
    }
    @Test void cancellationBeforeSubscriptionCancelsLateSubscriptionAndNeverRequestsData() {
        var body=new Bridge.LimitedBody();var cancelled=new AtomicBoolean();body.cancel();
        body.onSubscribe(new Flow.Subscription(){public void request(long n){fail("Cancelled body requested data");}public void cancel(){cancelled.set(true);}});
        body.onNext(java.util.List.of(ByteBuffer.wrap(new byte[1])));body.onComplete();
        assertTrue(cancelled.get());assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    static URI uri(HttpServer server,String path){return URI.create("http://localhost:"+server.getAddress().getPort()+path);}
}
