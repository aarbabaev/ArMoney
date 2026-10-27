package com.arman.bank.authservice.infrastructure

import spock.lang.Specification
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class MigrationUserReleaseWorkerSpec extends Specification {
    def 'construction is idle failures retain later work and close prevents any further tick'(){
        given:def calls=new AtomicInteger();def worker=new MigrationUserReleaseWorker({calls.incrementAndGet();throw new IllegalStateException('synthetic unavailable')} as Runnable)
        expect:calls.get()==0
        when:worker.tick();worker.tick();worker.close();worker.tick();worker.close()
        then:noExceptionThrown();calls.get()==2
        when:worker.start()
        then:thrown(IllegalStateException)
    }
    def 'one in flight tick prevents concurrent recovery and worker starts with delayed bounded cadence'(){
        given:def entered=new CountDownLatch(1);def release=new CountDownLatch(1);def calls=new AtomicInteger()
        def worker=new MigrationUserReleaseWorker({calls.incrementAndGet();entered.countDown();assert release.await(3,TimeUnit.SECONDS)} as Runnable)
        def executor=Executors.newSingleThreadExecutor()
        when:def running=executor.submit({worker.tick()} as Runnable);assert entered.await(3,TimeUnit.SECONDS);worker.tick()
        then:calls.get()==1
        when:release.countDown();running.get(3,TimeUnit.SECONDS);worker.start()
        then:calls.get()==1
        when:worker.start()
        then:thrown(IllegalStateException)
        cleanup:release?.countDown();worker?.close();executor?.shutdownNow();executor?.awaitTermination(3,TimeUnit.SECONDS)
    }
}
