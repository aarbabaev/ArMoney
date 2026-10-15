package com.arman.bank.authservice.infrastructure

import com.arman.bank.authservice.application.VerificationProjectionStore
import com.arman.bank.authservice.domain.VerificationProjection
import com.arman.bank.authservice.domain.VerificationProjection.*
import java.time.*
import spock.lang.Specification

class VerificationProjectionWorkerSpec extends Specification {
    static final Instant NOW=Instant.parse('2026-10-15T12:00:00Z')
    def store=Mock(VerificationProjectionStore)
    def transport=Mock(VerificationProjectionStore.Transport)
    def worker=new VerificationProjectionWorker(store,transport,'auth',Clock.fixed(NOW,ZoneOffset.UTC))
    def cleanup(){worker.close();Thread.interrupted()}
    static Claim claim(Instant expiry=NOW.plusSeconds(60),Instant lease=NOW.plusSeconds(30)){
        def command=VerificationProjection.create(UUID.randomUUID(),UUID.randomUUID(),0,1,'+971501234567',1,1L)
        new Claim('auth',command,HttpVerificationProjection.body(command),UUID.randomUUID(),lease,expiry,1)
    }
    def 'cleanup and committed claim precede one remote call and fenced finish'(){
        given:
        def item=claim()
        def ack=new Ack(item.command().operationId(),item.command().identityId(),1,1,1L,item.command().payloadHash())
        def result=new Result(Outcome.ACK,ack)
        when:
        worker.tick()
        then:
        1*store.cleanup()
        then:
        1*store.claim('auth')>>Optional.of(item)
        then:
        1*transport.send(item)>>result
        then:
        1*store.finish(item,result)>>true
    }
    def 'nonretryable HTTP outcome is forwarded without worker retry or new command allocation'(){
        given:
        def item=claim()
        def result=new Result(outcome,null)
        when:
        worker.tick()
        then:
        1*store.cleanup()
        1*store.claim('auth')>>Optional.of(item)
        1*transport.send(item)>>result
        1*store.finish(item,result)>>true
        0*store.allocate(_,_)
        where:
        outcome << [Outcome.BAD_REQUEST,Outcome.UNAUTHORIZED,Outcome.CONFLICT,Outcome.EXPIRED]
    }
    def 'expired command or expired lease cannot trigger network IO'(){
        given:
        def item=claim(expired?NOW:NOW.plusSeconds(60),expired?NOW.plusSeconds(30):NOW)
        when:
        worker.tick()
        then:
        1*store.cleanup()
        1*store.claim('auth')>>Optional.of(item)
        0*transport.send(_)
        1*store.finish(item,{it.outcome()==Outcome.RETRY})>>false
        where:
        expired << [true,false]
    }
    def 'interruption preserves lease uncertainty and interrupt flag'(){
        given:
        def item=claim()
        when:
        worker.tick()
        then:
        1*store.cleanup()
        1*store.claim('auth')>>Optional.of(item)
        1*transport.send(item)>>{throw new InterruptedException('synthetic')}
        0*store.finish(_,_)
        thrown(InterruptedException)
        Thread.currentThread().isInterrupted()
    }
    def 'closed worker owns transport cleanup and performs no further claims'(){
        when:
        worker.close()
        worker.tick()
        then:
        1*transport.close()
        0*store._
    }
}
