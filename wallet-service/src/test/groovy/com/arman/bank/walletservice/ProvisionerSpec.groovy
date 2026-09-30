package com.arman.bank.walletservice
import com.arman.bank.walletservice.application.*
import spock.lang.Specification
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
class ProvisionerSpec extends Specification {
    def "scheduler continues polling after a database outage"() {
        given:
        def calls = new AtomicInteger()
        def recovered = new CountDownLatch(1)
        ProvisioningStore store = Stub() {
            claim() >> {
                if (calls.incrementAndGet() == 1) throw new IllegalStateException('database offline')
                recovered.countDown()
                Optional.empty()
            }
        }
        def worker = new WalletProvisioner(store, Stub(LedgerAccounts))
        when:
        worker.start()
        then:
        recovered.await(5, TimeUnit.SECONDS)
        calls.get() >= 2
        cleanup:
        worker?.close()
    }
    def "shutdown is bounded and reports a worker stuck in a database operation"() {
        given:
        def entered = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        ProvisioningStore store = Stub() {
            claim() >> {
                entered.countDown()
                boolean released = false
                while (!released) {
                    try { release.await(); released = true }
                    catch (InterruptedException ignored) { /* Simulate an uninterruptible driver. */ }
                }
                Optional.empty()
            }
        }
        def worker = new WalletProvisioner(store, Stub(LedgerAccounts))
        worker.start()
        assert entered.await(3, TimeUnit.SECONDS)
        long start = System.nanoTime()
        when:
        worker.close()
        then:
        def failure = thrown(IllegalStateException)
        failure.message.contains('database must remain open')
        TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 13
        cleanup:
        release?.countDown()
        worker?.close()
    }
}
