package com.arman.bank.authservice

import com.arman.bank.authservice.application.IdentityVerificationService
import com.arman.bank.authservice.application.IdentityVerificationStore
import com.arman.bank.authservice.domain.IdentityVerification
import com.arman.bank.authservice.domain.IdentityVerification.*
import com.arman.bank.authservice.domain.Verification
import spock.lang.Specification

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class IdentityVerificationSpec extends Specification {
    static final String ISSUER='https://issuer.example/realms/armoney'
    static final String PHONE='+971501234567'
    static final String EMAIL='fixture@example.test'
    static final String WRITER='provider'
    static final Policy POLICY=Policy.defaults([ISSUER] as Set,['mobile'] as Set,[WRITER] as Set)
    static Flow flow(){new Flow(ISSUER,'subject','mobile','root','tab',Base64.urlEncoder.withoutPadding().encodeToString(new byte[32]))}
    static Observation evidence(Flow flow,Instant now){new Observation(flow,PHONE,EMAIL,PHONE,true,true,now.plusSeconds(60),now)}

    def 'staging states exclude banking eligibility and result versions are independent'(){
        given:
        def result=new Verification.Result(UUID.randomUUID(),null,null,7,'PENDING_CONTACTS',null,null,0,null,true,false,7,2,4)
        expect:
        State.values()*.name()==['PENDING_CONTACTS','PROJECTION_PENDING','PROJECTED','SUSPENDED','BLOCKED_MIGRATION']
        result.version()==7
        result.emailContactVersion()==2
        result.phoneContactVersion()==4
        result.emailVerified()
        !result.phoneVerified()
    }

    def 'fresh evidence is only a bounded pre-token observation'(){
        given:
        def now=Instant.parse('2026-10-15T10:00:00Z')
        def flow=flow()
        expect:
        IdentityVerification.validate(flow,POLICY)==null
        IdentityVerification.evidence(flow,evidence(flow,now),now)==null
        !evidence(flow,now).toString().contains(PHONE)
        !flow.toString().contains('subject')
    }

    def 'expired disabled mismatched and older than five second observations deny'(){
        given:
        def now=Instant.parse('2026-10-15T10:00:00Z')
        def flow=flow()
        def observation=new Observation(flow,PHONE,EMAIL,PHONE,enabled,active,now.plusSeconds(expiry),now.plusSeconds(start))
        when:
        IdentityVerification.evidence(flow,observation,now)
        then:
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.UNAVAILABLE
        where:
        enabled | active | expiry | start
        false   | true   | 60     | 0
        true    | false  | 60     | 0
        true    | true   | 0      | 0
        true    | true   | 60     | -6
        true    | true   | 60     | 1
    }

    def 'admission precedes one observation and denial prevents provider fanout'(){
        given:
        def store=Mock(IdentityVerificationStore)
        def observations=Mock(IdentityVerificationStore.Observations)
        def service=new IdentityVerificationService(store,observations,POLICY)
        def input=new Enrollment(UUID.randomUUID(),flow(),PHONE,EMAIL)
        when:
        service.enroll(WRITER,input)
        then:
        1 * store.admit(WRITER,input.flow()) >> {throw new Verification.Failure(Verification.Error.RATE_LIMITED)}
        0 * observations._
        0 * store.enroll(_,_,_,_)
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.RATE_LIMITED
    }

    def 'business rejection is raised after repository returns allowing attempts to commit'(){
        given:
        def store=Mock(IdentityVerificationStore)
        def observations=Mock(IdentityVerificationStore.Observations)
        def service=new IdentityVerificationService(store,observations,POLICY)
        def input=new Consume(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),flow(),Verification.Purpose.REGISTRATION_PHONE,2,'123456')
        def observation=evidence(input.flow(),Instant.now())
        when:
        service.consume(WRITER,input)
        then:
        1 * store.admit(WRITER,input.flow()) >> observation.observationStartedAt()
        1 * observations.observe(input.flow(),observation.observationStartedAt()) >> observation
        1 * store.consume(WRITER,input,observation,observation.observationStartedAt()) >> Verification.Result.rejected(Verification.Error.CONFLICT)
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.CONFLICT
    }

    def 'malformed command does not consume admission or observation'(){
        given:
        def store=Mock(IdentityVerificationStore)
        def observations=Mock(IdentityVerificationStore.Observations)
        def service=new IdentityVerificationService(store,observations,POLICY)
        when:
        service.issue(WRITER,new Issue(null,UUID.randomUUID(),flow(),Verification.Purpose.REGISTRATION_EMAIL,1))
        then:
        thrown(Verification.Failure)
        0 * store._
        0 * observations._
    }

    def 'sixteen in flight operations bound provider fanout and permits recover after failure'(){
        given:
        def entered=new CountDownLatch(16)
        def release=new CountDownLatch(1)
        def calls=new AtomicInteger()
        def store=Stub(IdentityVerificationStore){admit(_,_) >> {Instant.now()}}
        IdentityVerificationStore.Observations observations={ Flow flow,Instant startedAt ->
            calls.incrementAndGet()
            entered.countDown()
            if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException('fixture timeout')
            throw new Verification.Failure(Verification.Error.UNAVAILABLE)
        } as IdentityVerificationStore.Observations
        def service=new IdentityVerificationService(store,observations,POLICY)
        def pool=Executors.newFixedThreadPool(16)
        def enrollment=UUID.randomUUID()
        def tasks=(1..16).collect {pool.submit({try{service.status(WRITER,enrollment,flow());return null}catch(Verification.Failure failure){return failure.error()}} as java.util.concurrent.Callable)}
        expect:
        entered.await(5,TimeUnit.SECONDS)
        when:
        service.status(WRITER,enrollment,flow())
        then:
        def rejection=thrown(Verification.Failure)
        rejection.error()==Verification.Error.RATE_LIMITED
        calls.get()==16
        when:
        release.countDown()
        def outcomes=tasks.collect{it.get(5,TimeUnit.SECONDS)}
        service.status(WRITER,enrollment,flow())
        then:
        outcomes.every{it==Verification.Error.UNAVAILABLE}
        def subsequent=thrown(Verification.Failure)
        subsequent.error()==Verification.Error.UNAVAILABLE
        calls.get()==17
        cleanup:
        release.countDown()
        pool.shutdownNow()
        pool.awaitTermination(5,TimeUnit.SECONDS)
    }

    def 'observer cannot refresh the service owned admission timestamp'(){
        given:
        def store=Mock(IdentityVerificationStore)
        def observations=Mock(IdentityVerificationStore.Observations)
        def service=new IdentityVerificationService(store,observations,POLICY)
        def input=new Enrollment(UUID.randomUUID(),flow(),PHONE,EMAIL)
        def started=Instant.parse('2026-10-15T10:00:00Z')
        when:
        service.enroll(WRITER,input)
        then:
        1 * store.admit(WRITER,input.flow()) >> started
        1 * observations.observe(input.flow(),started) >> evidence(input.flow(),started.plusSeconds(1))
        0 * store.enroll(_,_,_,_)
        def failure=thrown(Verification.Failure)
        failure.error()==Verification.Error.UNAVAILABLE
    }
}
