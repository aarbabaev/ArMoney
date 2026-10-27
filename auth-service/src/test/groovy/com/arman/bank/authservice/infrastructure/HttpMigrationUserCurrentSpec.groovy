package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.MigrationUserCurrentPort
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import spock.lang.Specification
import java.time.Duration

/** Synthetic HTTP peer: transport/correlation evidence only, no genuine User or banking authority. */
class HttpMigrationUserCurrentSpec extends Specification {
    def records=new java.util.concurrent.CopyOnWriteArrayList<java.util.logging.LogRecord>()
    def logger=java.util.logging.Logger.getLogger(HttpMigrationUserCurrent.name)
    def handler=new java.util.logging.Handler(){void publish(java.util.logging.LogRecord record){records.add(record)};void flush(){};void close(){}}
    def setup(){logger.addHandler(handler)}
    def cleanup(){logger.removeHandler(handler)}
    def 'default OFF creates no client'(){
        expect:
        HttpMigrationUserCurrent.open(MigrationUserRevisionConfiguration.from([:])).empty
    }
    def 'strict response parser rejects duplicate fractional exponent overflow and oversized bodies'(){
        when:
        HttpMigrationUserCurrent.parse(text.bytes)
        then:
        thrown(IllegalStateException)
        where:
        text << ['{"x":1,"x":2}','{"x":1.0}','{"x":1e0}','{"x":9223372036854775808}', ' '*4097]
    }
    def 'authenticated exact CURRENT response returns only bound User tuple'(){
        given:
        def json=new ObjectMapper()
        def query=new MigrationUserCurrentPort.Query(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,2,3)
        def requests=[]
        def server=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        server.createContext(HttpMigrationUserCurrent.PATH){ exchange ->
            requests.add([exchange.requestMethod,exchange.requestHeaders.getFirst(HttpMigrationUserCurrent.HEADER)])
            def request=json.readValue(exchange.requestBody,Map)
            def response=new LinkedHashMap(request)
            response.putAll([status:'CURRENT',current:[profile_id:UUID.randomUUID().toString(),shard_id:'primary',baseline_generation:0,contact_mutation_revision:0,projection_present:false,projection_revision:null,phone_contact_version:null,verification_epoch:null],observed_at_us:0])
            byte[] bytes=json.writeValueAsBytes(response)
            exchange.responseHeaders.set('Content-Type','application/json');exchange.sendResponseHeaders(200,bytes.length)
            exchange.responseBody.write(bytes);exchange.close()
        }
        server.start()
        def config=MigrationUserRevisionConfiguration.from(MigrationUserRevisionConfigurationSpec.environment())
        def client=new HttpMigrationUserCurrent(config,URI.create('http://127.0.0.1:'+server.address.port),HttpCodeSessionObservations.privateClient())
        when:
        def observation=client.observe(query,Duration.ofSeconds(4))
        then:
        observation.query()==query
        observation.current().projection_present==false
        requests==[['POST','c'*32]]
        records.empty
        cleanup:
        client?.close();server?.stop(0)
    }
    def 'all non200 responses remain unavailable and never return negative evidence'(){
        given:
        def calls=new java.util.concurrent.atomic.AtomicInteger()
        def server=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        server.createContext(HttpMigrationUserCurrent.PATH){ exchange -> calls.incrementAndGet();exchange.sendResponseHeaders(status,-1);exchange.close() }
        server.start()
        def config=MigrationUserRevisionConfiguration.from(MigrationUserRevisionConfigurationSpec.environment())
        def client=new HttpMigrationUserCurrent(config,URI.create('http://127.0.0.1:'+server.address.port),HttpCodeSessionObservations.privateClient())
        when:
        client.observe(new MigrationUserCurrentPort.Query(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,1),Duration.ofSeconds(4))
        then:
        thrown(IllegalStateException)
        calls.get()==1
        records*.message==['armoney_auth_user_current_failure category='+(status==301?'HTTP_OTHER':'HTTP_'+status)]
        records.every{it.thrown==null && !it.parameters}
        cleanup:
        client?.close();server?.stop(0)
        where:
        status << [301,400,401,403,404,409,503]
    }
    def 'malformed and unbound success responses expose only fixed failure category'(){
        given:
        def calls=new java.util.concurrent.atomic.AtomicInteger()
        def server=HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        server.createContext(HttpMigrationUserCurrent.PATH){ exchange ->
            calls.incrementAndGet();exchange.requestBody.readAllBytes()
            byte[] bytes=payload.bytes
            exchange.responseHeaders.set('Content-Type','application/json');exchange.sendResponseHeaders(200,bytes.length)
            exchange.responseBody.write(bytes);exchange.close()
        }
        server.start()
        def config=MigrationUserRevisionConfiguration.from(MigrationUserRevisionConfigurationSpec.environment())
        def client=new HttpMigrationUserCurrent(config,URI.create('http://127.0.0.1:'+server.address.port),HttpCodeSessionObservations.privateClient())
        when:client.observe(new MigrationUserCurrentPort.Query(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,1),Duration.ofSeconds(4))
        then:
        def failure=thrown(IllegalStateException)
        failure.cause==null
        calls.get()==1
        records*.message==['armoney_auth_user_current_failure category='+category]
        records.every{it.thrown==null && !it.parameters}
        cleanup:client?.close();server?.stop(0)
        where:
        payload | category
        'private invalid response' | 'RESPONSE_PARSE'
        '{}' | 'RESPONSE_BINDING'
    }
}
