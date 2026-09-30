package com.arman.bank.ledgerservice
import com.arman.bank.runtime.Database
import com.arman.bank.ledgerservice.application.*
import com.arman.bank.ledgerservice.domain.*
import com.arman.bank.ledgerservice.infrastructure.PostgresLedger
import org.testcontainers.containers.PostgreSQLContainer
import org.jooq.exception.DataAccessException
import spock.lang.Specification
import java.util.concurrent.*
import static com.arman.bank.ledgerservice.domain.TransferResult.Outcome.*

class LedgerEngineIntegrationSpec extends Specification {
    PostgreSQLContainer postgres
    Database db
    LedgerService service
    UUID alice = UUID.randomUUID()
    UUID bob = UUID.randomUUID()
    Account a
    Account b
    def setup() {
        postgres = new PostgreSQLContainer('postgres:17.6-alpine')
        postgres.start()
        db = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        service = new LedgerService(new PostgresLedger(db))
        a = service.open(alice, UUID.randomUUID(), 'EUR')
        b = service.open(bob, UUID.randomUUID(), 'EUR')
    }
    def cleanup() { db?.close(); postgres?.stop() }
    def fund(Account target, long amount) {
        // Test-only clearing counterpart. Real journal entries, never an unexplained balance UPDATE.
        db.transaction { sql ->
            def reserve = UUID.randomUUID()
            sql.execute("insert into accounts(id, wallet_id, currency, account_kind) values (?, ?, ?, 'CLEARING')",
                reserve, UUID.randomUUID(), target.currency())
            sql.execute("insert into transfers(payment_id, debit_account_id, credit_account_id, currency, amount_minor) values (?, ?, ?, ?, ?)",
                UUID.randomUUID(), reserve, target.id(), target.currency(), amount)
        }
    }
    Transfer transfer(long amount, UUID payment = UUID.randomUUID(), UUID debit = a.id(), UUID credit = b.id(), String currency = 'EUR') {
        new Transfer(payment, debit, credit, Currency.getInstance(currency), amount)
    }
    long balance(UUID owner, Account account) { service.account(owner, account.id()).get().balanceMinor() }
    def assertReconciled() {
        assert db.transaction { sql -> sql.fetchOne("""
            select count(*) from accounts a
            where a.balance_minor <> coalesce((select sum(p.amount_minor) from postings p where p.account_id = a.id), 0)
            """).get(0, Integer) } == 0
        assert db.transaction { it.fetchOne('select coalesce(sum(balance_minor::numeric),0) from accounts').get(0, BigDecimal) } == 0
        assert db.transaction { it.fetchOne('select count(*) from (select payment_id from postings group by payment_id having count(*) <> 2 or sum(amount_minor) <> 0) p').get(0, Integer) } == 0
    }

    def "post replay lookup and restart preserve one balanced immutable result"() {
        given:
        fund(a, 1000)
        def t = transfer(250)
        when:
        def first = service.post(alice, t)
        def again = service.post(alice, t)
        then:
        first == again
        first.outcome() == POSTED
        balance(alice, a) == 750
        balance(bob, b) == 250
        service.result(alice, t.paymentId()).get() == first
        service.result(bob, t.paymentId()).isEmpty()
        service.account(bob, a.id()).isEmpty()
        when:
        db.close()
        db = new Database(postgres.jdbcUrl, postgres.username, postgres.password)
        service = new LedgerService(new PostgresLedger(db))
        then:
        service.post(alice, t) == first
        balance(alice, a) == 750
        when:
        db.transaction { it.execute('update transfers set amount_minor = 1 where payment_id = ?', t.paymentId()) }
        then:
        thrown(DataAccessException)
        when:
        db.transaction { it.execute('delete from transfer_requests where payment_id = ?', t.paymentId()) }
        then:
        thrown(DataAccessException)
        when:
        db.transaction { it.execute('truncate transfers') }
        then:
        thrown(DataAccessException)
        cleanup:
        assertReconciled()
    }

    def "insufficient funds is durable and a changed payload or requester conflicts"() {
        given:
        def t = transfer(10)
        expect:
        service.post(alice, t).outcome() == INSUFFICIENT_FUNDS
        when:
        fund(a, 100)
        then:
        service.post(alice, t).outcome() == INSUFFICIENT_FUNDS
        balance(alice, a) == 100
        balance(bob, b) == 0
        when:
        service.post(alice, transfer(11, t.paymentId()))
        then:
        thrown(LedgerConflict)
        when:
        service.post(bob, t)
        then:
        thrown(LedgerConflict)
        cleanup:
        assertReconciled()
    }

    def "concurrent distinct commands cannot overspend"() {
        given:
        fund(a, 100)
        def executor = Executors.newFixedThreadPool(8)
        when:
        def results = (1..20).collect { executor.submit({ service.post(alice, transfer(10)) } as Callable) }
            .collect { it.get(20, TimeUnit.SECONDS) }
        then:
        results.count { it.outcome() == POSTED } == 10
        results.count { it.outcome() == INSUFFICIENT_FUNDS } == 10
        balance(alice, a) == 0
        balance(bob, b) == 100
        cleanup:
        executor?.shutdownNow()
        assertReconciled()
    }

    def "concurrent identical commands post exactly once"() {
        given:
        fund(a, 100)
        def t = transfer(10)
        def executor = Executors.newFixedThreadPool(8)
        when:
        def results = (1..16).collect { executor.submit({ service.post(alice, t) } as Callable) }
            .collect { it.get(20, TimeUnit.SECONDS) }
        then:
        results.every { it.outcome() == POSTED }
        balance(alice, a) == 90
        balance(bob, b) == 10
        db.transaction { it.fetchOne('select count(*) from transfer_requests').get(0, Integer) } == 1
        cleanup:
        executor?.shutdownNow()
        assertReconciled()
    }

    def "opposite direction commands lock accounts consistently"() {
        given:
        fund(a, 100)
        fund(b, 100)
        def executor = Executors.newFixedThreadPool(8)
        when:
        def results = (1..20).collect { i ->
            executor.submit({ i % 2 == 0 ? service.post(alice, transfer(1)) :
                service.post(bob, transfer(1, UUID.randomUUID(), b.id(), a.id())) } as Callable)
        }.collect { it.get(20, TimeUnit.SECONDS) }
        then:
        results.every { it.outcome() == POSTED }
        balance(alice, a) == 100
        balance(bob, b) == 100
        cleanup:
        executor?.shutdownNow()
        assertReconciled()
    }

    def "overflow wrong currency missing account and unauthorized debit do not move funds"() {
        given:
        fund(a, 100)
        fund(b, Long.MAX_VALUE)
        expect:
        service.post(alice, transfer(1)).outcome() == BALANCE_LIMIT
        service.post(bob, transfer(1)).outcome() == INVALID_ACCOUNT
        service.post(alice, transfer(1, UUID.randomUUID(), a.id(), b.id(), 'USD')).outcome() == INVALID_ACCOUNT
        service.post(alice, transfer(1, UUID.randomUUID(), a.id(), UUID.randomUUID())).outcome() == INVALID_ACCOUNT
        balance(alice, a) == 100
        balance(bob, b) == Long.MAX_VALUE
        cleanup:
        assertReconciled()
    }

    def "late SQL failure rolls back journal both balances and reservation and permits retry"() {
        given:
        fund(a, 100)
        def t = transfer(10)
        db.transaction { sql ->
            sql.execute("""create function force_failure() returns trigger language plpgsql as
                'begin raise exception ''injected failure''; end;'""")
            sql.execute('create trigger zz_failure after insert on transfers for each row execute function force_failure()')
        }
        when:
        service.post(alice, t)
        then:
        thrown(DataAccessException)
        balance(alice, a) == 100
        balance(bob, b) == 0
        service.result(alice, t.paymentId()).isEmpty()
        db.transaction { it.fetchOne('select count(*) from transfers where payment_id = ?', t.paymentId()).get(0, Integer) } == 0
        when:
        db.transaction { it.execute('drop trigger zz_failure on transfers') }
        then:
        service.post(alice, t).outcome() == POSTED
        balance(alice, a) == 90
        cleanup:
        assertReconciled()
    }

    def "account provisioning retries converge and reject owner currency remapping"() {
        given:
        def wallet = UUID.randomUUID()
        def executor = Executors.newFixedThreadPool(8)
        when:
        def accounts = (1..16).collect { executor.submit({ service.open(alice, wallet, 'GBP') } as Callable) }
            .collect { it.get(20, TimeUnit.SECONDS) }
        then:
        accounts*.id().unique().size() == 1
        accounts.every { it.balanceMinor() == 0 }
        when:
        service.open(bob, wallet, 'GBP')
        then:
        thrown(LedgerConflict)
        when:
        service.open(alice, wallet, 'EUR')
        then:
        thrown(LedgerConflict)
        cleanup:
        executor?.shutdownNow()
    }

    def "database rejects direct overdraft even outside application and unfinished commands cannot commit"() {
        when:
        db.transaction { it.execute('insert into transfers(payment_id, debit_account_id, credit_account_id, currency, amount_minor) values (?, ?, ?, ?, ?)',
            UUID.randomUUID(), a.id(), b.id(), 'EUR', 1L) }
        then:
        thrown(DataAccessException)
        balance(alice, a) == 0
        balance(bob, b) == 0
        when:
        db.transaction { it.execute("insert into transfer_requests values (?, ?, ?, ?, ?, ?, 'PENDING', current_timestamp)",
            UUID.randomUUID(), alice, a.id(), b.id(), 'EUR', 1L) }
        then:
        thrown(DataAccessException)
        cleanup:
        assertReconciled()
    }
}
