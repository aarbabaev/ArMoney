package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.domain.LegacyMigrationFinalization as F
import spock.lang.Specification
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import groovy.json.JsonOutput

class LegacyMigrationFinalizationSpec extends Specification {
    static Map evidence(){
        [protocol_version:1L,migration_id:'00000000-0000-0000-0000-000000000001',decision_id:'00000000-0000-0000-0000-000000000002',stage_operation_id:'00000000-0000-0000-0000-000000000003',identity_id:'00000000-0000-0000-0000-000000000004',baseline_hash:'a'*64,stage_ack_hash:'b'*64,email_contact_version:1L,email_digest:'c'*64,email_receipt_id:'00000000-0000-0000-0000-000000000005',email_receipt_generation:1L,phone_receipt_id:'00000000-0000-0000-0000-000000000006',phone_receipt_generation:1L,receipt_snapshot_hash:'0'*64,policy_version:1L,payload_hash:'d'*64,state:'PUBLICATION_AUTHORIZED']
    }
    def 'exact User receipt wire order uses original unkeyed length prefixed codec'(){
        given:
        def fields=evidence()
        def userFields=['protocol_version','migration_id','decision_id','stage_operation_id','identity_id','baseline_hash','stage_ack_hash','email_contact_version','email_digest','email_receipt_id','email_receipt_generation','phone_receipt_id','phone_receipt_generation','policy_version','payload_hash']
        def digest=MessageDigest.getInstance('SHA-256')
        (['armoney-legacy-migration-receipts-v1']+userFields.collect{String.valueOf(fields[it])}).each{value->byte[] bytes=value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes)}
        fields.receipt_snapshot_hash=HexFormat.of().formatHex(digest.digest())
        when:def proof=new F.Evidence(fields)
        fields.state='COMPLETED'
        then:proof.fields().state=='PUBLICATION_AUTHORIZED'
        proof.toString()=='MigrationPublicationEvidence[redacted]'
        when:proof.fields().clear()
        then:thrown(UnsupportedOperationException)
    }
    def 'publication evidence never accepts fabricated terminal state or wrong receipt namespace'(){
        given:def fields=evidence();fields.receipt_snapshot_hash=F.receiptHash(fields);fields[name]=value
        when:new F.Evidence(fields)
        then:thrown(F.Failure)
        where:
        name | value
        'state' | 'FINALIZED'
        'state' | 'ABORTED'
        'protocol_version' | true
        'email_contact_version' | 0L
        'email_receipt_generation' | 1.0d
        'phone_receipt_id' | '00000000-0000-0000-0000-000000000005'
        'receipt_snapshot_hash' | ('f'*64)
    }
    def 'journal timestamp normalization is microsecond UTC without changing User codec'(){
        expect:
        F.instant(Instant.parse('2026-10-18T01:02:03.123456789Z'))=='2026-10-18T01:02:03.123456Z'
        F.instant(Instant.parse('2026-10-18T01:02:03Z'))=='2026-10-18T01:02:03Z'
        F.number(0L,true)==0L
        F.number(Long.MAX_VALUE,true)==Long.MAX_VALUE
    }
    def 'counter parsing never coerces negative boolean floating or overflow values'(){
        when:F.number(value,true)
        then:thrown(F.Failure)
        where:value << [-1L,true,0.0d,new BigInteger('9223372036854775808')]
    }
    def 'strict internal decoder rejects duplicate trailing oversized and coerced numeric JSON'(){
        when:LegacyMigrationFinalizationRoutes.parse(body.getBytes(StandardCharsets.UTF_8))
        then:thrown(F.Failure)
        where:body << ['{"a":1,"a":2}','{} {}','{"a":1e0}','{"a":1.0}','{"a":9223372036854775808}',(' '*12289),'null']
    }
    def 'decision shape never supplies finalization or identity authority'(){
        given:def fields=[decision_id:UUID.randomUUID().toString(),migration_id:UUID.randomUUID().toString(),stage_operation_id:UUID.randomUUID().toString(),payload_hash:'a'*64,state:F.STATE]
        expect:new F.Decision(fields).fields().keySet()==F.DECISION.toSet()
        when:fields.identity_id=UUID.randomUUID().toString();new F.Decision(fields)
        then:thrown(F.Failure)
    }
    def 'authenticated stalled or dripping HTTP body closes physically within original four seconds and releases admission'(){
        given:
        int calls=0
        def store=[inRequest:{a,b,c->throw new AssertionError('no creator route')},decision:{q->calls++;throw new F.Failure(F.Error.UNAVAILABLE)},evidence:{q->calls++;throw new F.Failure(F.Error.UNAVAILABLE)}] as com.arman.bank.authservice.application.LegacyMigrationFinalizationStore
        def routes=new LegacyMigrationFinalizationRoutes(store,'s'*32,true)
        def app=io.javalin.Javalin.create{config->routes.configure(config)}.start(0)
        def socket=new Socket('127.0.0.1',app.port());socket.soTimeout=7000
        def out=socket.outputStream
        out.write(('POST '+LegacyMigrationFinalizationRoutes.PREFIX+'decision HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n'+LegacyMigrationFinalizationRoutes.HEADER+': '+('s'*32)+'\r\nContent-Length: 12000\r\nConnection: close\r\n\r\n').getBytes(StandardCharsets.US_ASCII));out.flush()
        Thread dripper=null
        if(drip){dripper=new Thread({try{while(!Thread.currentThread().isInterrupted()){Thread.sleep(200);out.write(32);out.flush()}}catch(IOException ignoredReadClosure){}catch(InterruptedException cancelled){Thread.currentThread().interrupt()}});dripper.start()}
        long started=System.nanoTime()
        when:
        boolean closed=false
        try{while(socket.inputStream.read()!=-1){};closed=true}catch(java.net.SocketException physicalClose){closed=true}
        long elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)
        def field=LegacyMigrationFinalizationRoutes.getDeclaredField('calls');field.accessible=true
        long releaseBound=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1)
        while(field.get(routes).availablePermits()!=16&&System.nanoTime()<releaseBound)Thread.sleep(10)
        then:
        closed
        elapsed>=2500 && elapsed<6500
        field.get(routes).availablePermits()==16
        calls==0
        cleanup:
        socket?.close();dripper?.interrupt();dripper?.join(2000);app?.stop()
        where:drip << [false,true]
    }
}
