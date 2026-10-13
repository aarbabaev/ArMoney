package com.arman.bank.ledgerservice

import com.arman.bank.runtime.Database
import com.arman.bank.ledgerservice.application.LedgerConflict
import com.arman.bank.ledgerservice.domain.Transfer
import com.arman.bank.ledgerservice.infrastructure.*
import org.jooq.exception.DataAccessException
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Specification
import java.util.concurrent.*

class LocalDemoFundingIntegrationSpec extends Specification {
    static final Map GATE = [APP_ENV:'local', ARMONEY_LOCAL_DEMO_FUNDING:'I_UNDERSTAND_SYNTHETIC_FUNDS']
    PostgreSQLContainer pg
    Database db
    PostgresLedger ledger
    LocalDemoFunding funding
    def account
    def setup() {
        pg = new PostgreSQLContainer('postgres:17.6-alpine')
        pg.start()
        db = new Database(pg.jdbcUrl, pg.username, pg.password)
        ledger = new PostgresLedger(db)
        funding = new LocalDemoFunding(db, GATE)
        account = ledger.open(UUID.randomUUID(), UUID.randomUUID(), 'AED')
    }
    def cleanup() { db?.close(); pg?.stop() }
    def command(UUID id = UUID.randomUUID(), long amount = 100000L) {
        new LocalDemoFunding.Command(id, account.ownerId(), account.walletId(), account.id(), amount)
    }
    def reconciled() {
        assert db.transaction { it.fetchOne('select count(*) from accounts a where balance_minor <> coalesce((select sum(amount_minor) from postings p where p.account_id=a.id),0)').get(0, Integer) } == 0
        assert db.transaction { it.fetchOne('select coalesce(sum(balance_minor::numeric),0) from accounts').get(0, BigDecimal) } == 0
        assert db.transaction { it.fetchOne('select count(*) from (select payment_id from postings group by payment_id having count(*) <> 2 or sum(amount_minor) <> 0) p').get(0, Integer) } == 0
        true
    }

    def 'concurrent retry and restart fund once and preserve reconciled results after spending to zero'() {
        given:
        def c = command()
        def pool = Executors.newFixedThreadPool(8)
        expect:
        account.balanceMinor() == 0
        when:
        def results = (1..16).collect { pool.submit({ funding.fund(c) } as Callable) }.collect { it.get(30, TimeUnit.SECONDS) }
        then:
        results.every { it == 'POSTED' }
        ledger.account(account.ownerId(), account.id()).get().balanceMinor() == 100000
        db.transaction { it.fetchOne('select count(*) from transfers').get(0, Integer) } == 1
        reconciled()
        when:
        def receiver = ledger.open(UUID.randomUUID(), UUID.randomUUID(), 'AED')
        ledger.post(account.ownerId(), new Transfer(UUID.randomUUID(), account.id(), receiver.id(), Currency.getInstance('AED'), 100000))
        db.close()
        db = new Database(pg.jdbcUrl, pg.username, pg.password)
        funding = new LocalDemoFunding(db, GATE)
        ledger = new PostgresLedger(db)
        then:
        funding.fund(c) == 'POSTED'
        ledger.account(account.ownerId(), account.id()).get().balanceMinor() == 0
        when:
        funding.fund(command())
        then:
        thrown(LedgerConflict)
        reconciled()
        cleanup:
        pool?.shutdownNow()
    }

    def 'changed amount requester wallet or account conflicts and new ID cannot refill'() {
        given:
        def c = command()
        funding.fund(c)
        when:
        funding.fund(command(c.fundingId(), 200000))
        then:
        thrown(LedgerConflict)
        when:
        funding.fund(new LocalDemoFunding.Command(c.fundingId(), UUID.randomUUID(), c.walletId(), c.accountId(), c.amountFils()))
        then:
        thrown(LedgerConflict)
        when:
        funding.fund(new LocalDemoFunding.Command(c.fundingId(), c.ownerId(), UUID.randomUUID(), c.accountId(), c.amountFils()))
        then:
        thrown(LedgerConflict)
        when:
        funding.fund(new LocalDemoFunding.Command(c.fundingId(), c.ownerId(), c.walletId(), UUID.randomUUID(), c.amountFils()))
        then:
        thrown(LedgerConflict)
        when:
        funding.fund(command())
        then:
        thrown(LedgerConflict)
        reconciled()
    }

    def 'late failure rolls back reservation clearing account journal and balances then permits retry'() {
        given:
        def c = command()
        db.transaction {
            it.execute("create function force_failure() returns trigger language plpgsql as 'begin raise exception ''injected failure''; end;'")
            it.execute('create trigger zz_failure after insert on transfers for each row execute function force_failure()')
        }
        when:
        funding.fund(c)
        then:
        thrown(DataAccessException)
        ledger.account(account.ownerId(), account.id()).get().balanceMinor() == 0
        db.transaction { it.fetchOne('select count(*) from transfer_requests').get(0, Integer) } == 0
        db.transaction { it.fetchOne('select count(*) from transfers').get(0, Integer) } == 0
        db.transaction { it.fetchOne("select count(*) from accounts where account_kind='CLEARING'").get(0, Integer) } == 0
        when:
        db.transaction { it.execute('drop trigger zz_failure on transfers') }
        then:
        funding.fund(c) == 'POSTED'
        reconciled()
    }

    def 'missing or wrong owner target and clearing identity collision fail without funding'() {
        when:
        funding.fund(new LocalDemoFunding.Command(UUID.randomUUID(), account.ownerId(), account.walletId(), UUID.randomUUID(), 100000))
        then:
        thrown(LedgerConflict)
        when:
        funding.fund(new LocalDemoFunding.Command(UUID.randomUUID(), UUID.randomUUID(), account.walletId(), account.id(), 100000))
        then:
        thrown(LedgerConflict)
        when:
        db.transaction { it.execute('insert into accounts(id,wallet_id,owner_id,currency) values (?,?,?,?)', LocalDemoFunding.CLEARING_ID, UUID.randomUUID(), UUID.randomUUID(), 'AED') }
        funding.fund(command())
        then:
        thrown(LedgerConflict)
        db.transaction { it.fetchOne('select count(*) from transfer_requests').get(0, Integer) } == 0
        reconciled()
    }

    def 'different funding IDs race for one pristine account and only one commits'() {
        given:
        def pool = Executors.newFixedThreadPool(2)
        when:
        def results = (1..2).collect { pool.submit({
            try { funding.fund(command()) } catch (LedgerConflict ignored) { 'CONFLICT' }
        } as Callable) }.collect { it.get(30, TimeUnit.SECONDS) }
        then:
        results.sort() == ['CONFLICT','POSTED']
        ledger.account(account.ownerId(), account.id()).get().balanceMinor() == 100000
        reconciled()
        cleanup:
        pool?.shutdownNow()
    }
    def 'clearing arithmetic overflow rolls back without changing a pristine target'() {
        given:
        def other = ledger.open(UUID.randomUUID(), UUID.randomUUID(), 'AED')
        def another = ledger.open(UUID.randomUUID(), UUID.randomUUID(), 'AED')
        db.transaction {
            it.execute("insert into accounts(id,wallet_id,currency,account_kind) values (?,?,'AED','CLEARING')", LocalDemoFunding.CLEARING_ID, LocalDemoFunding.CLEARING_WALLET_ID)
            it.execute("insert into transfers(payment_id,debit_account_id,credit_account_id,currency,amount_minor) values (?,?,?,'AED',?)", UUID.randomUUID(), LocalDemoFunding.CLEARING_ID, other.id(), Long.MAX_VALUE)
            it.execute("insert into transfers(payment_id,debit_account_id,credit_account_id,currency,amount_minor) values (?,?,?,'AED',?)", UUID.randomUUID(), LocalDemoFunding.CLEARING_ID, another.id(), 1L)
        }
        when:
        funding.fund(command())
        then:
        thrown(ArithmeticException)
        ledger.account(account.ownerId(), account.id()).get().balanceMinor() == 0
        db.transaction { it.fetchOne('select count(*) from transfer_requests').get(0, Integer) } == 0
        reconciled()
    }
}

