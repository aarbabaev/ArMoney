package com.arman.bank.ledgerservice.infrastructure;
import com.arman.bank.ledgerservice.application.*;
import com.arman.bank.ledgerservice.domain.*;
import com.arman.bank.runtime.Database;
import org.jooq.DSLContext;
import java.util.*;
import static com.arman.bank.ledgerservice.domain.TransferResult.Outcome.*;

public final class PostgresLedger implements LedgerStore {
    private final Database db;
    private final LedgerReads reads;
    public PostgresLedger(Database db) { this(db, null); }
    public PostgresLedger(Database db, LedgerReads reads) { this.db = db; this.reads = reads; }

    @Override public Account open(UUID owner, UUID wallet, String currency) {
        return confirmed(db.transaction(sql -> {
            sql.execute("insert into accounts(id, wallet_id, owner_id, currency) values (?, ?, ?, ?) on conflict(wallet_id) do nothing",
                UUID.randomUUID(), wallet, owner, currency);
            var row = sql.fetchOne("select * from accounts where wallet_id = ?", wallet);
            if (!owner.equals(row.get("owner_id", UUID.class)) || !currency.equals(row.get("currency", String.class)) ||
                !"CUSTOMER".equals(row.get("account_kind", String.class))) throw new LedgerConflict();
            return account(row);
        }));
    }
    @Override public Optional<Account> account(UUID owner, UUID id) {
        java.util.function.Function<DSLContext, Optional<Account>> query = sql -> Optional.ofNullable(sql.fetchOne(
            "select * from accounts where id = ? and owner_id = ? and account_kind = 'CUSTOMER'", id, owner)).map(PostgresLedger::account);
        return reads == null ? db.transaction(query) : reads.read(query);
    }
    @Override public Optional<TransferResult> result(UUID requester, UUID payment) {
        return confirmed(db.transaction(sql -> Optional.ofNullable(sql.fetchOne(
            "select * from transfer_requests where payment_id = ? and requester_id = ?", payment, requester)).map(PostgresLedger::result)));
    }
    @Override public TransferResult post(UUID requester, Transfer transfer) {
        return confirmed(db.transaction(sql -> {
            // Reserve the id first. Conflicting inserts wait until the winning transaction commits or rolls back.
            int inserted = sql.execute("""
                insert into transfer_requests(payment_id, requester_id, debit_account_id, credit_account_id, currency, amount_minor, outcome)
                values (?, ?, ?, ?, ?, ?, 'PENDING') on conflict(payment_id) do nothing
                """, transfer.paymentId(), requester, transfer.debitAccountId(), transfer.creditAccountId(),
                transfer.currency().getCurrencyCode(), transfer.amountMinor());
            var request = sql.fetchOne("select * from transfer_requests where payment_id = ?", transfer.paymentId());
            if (!requester.equals(request.get("requester_id", UUID.class)) || !transfer.equals(transfer(request)))
                throw new LedgerConflict();
            if (inserted == 0) return result(request);

            // SQL UUID ordering is the same for every command, including transfers in opposite directions.
            var locked = sql.fetch("select * from accounts where id in (?, ?) order by id for update",
                transfer.debitAccountId(), transfer.creditAccountId());
            var debit = locked.stream().filter(r -> transfer.debitAccountId().equals(r.get("id", UUID.class))).findFirst().orElse(null);
            var credit = locked.stream().filter(r -> transfer.creditAccountId().equals(r.get("id", UUID.class))).findFirst().orElse(null);
            var outcome = POSTED;
            String currency = transfer.currency().getCurrencyCode();
            if (debit == null || credit == null || !requester.equals(debit.get("owner_id", UUID.class)) ||
                !"CUSTOMER".equals(debit.get("account_kind", String.class)) || !"CUSTOMER".equals(credit.get("account_kind", String.class)) ||
                credit.get("owner_id", UUID.class) == null || !currency.equals(debit.get("currency", String.class)) ||
                !currency.equals(credit.get("currency", String.class))) {
                outcome = INVALID_ACCOUNT;
            } else if (debit.get("balance_minor", Long.class) < transfer.amountMinor()) {
                outcome = INSUFFICIENT_FUNDS;
            } else if (credit.get("balance_minor", Long.class) > Long.MAX_VALUE - transfer.amountMinor()) {
                outcome = BALANCE_LIMIT;
            }
            if (outcome == POSTED) {
                // The DB trigger updates both balances. Any insert/trigger/commit failure rolls back the reservation too.
                sql.execute("insert into transfers(payment_id, debit_account_id, credit_account_id, currency, amount_minor) values (?, ?, ?, ?, ?)",
                    transfer.paymentId(), transfer.debitAccountId(), transfer.creditAccountId(), currency, transfer.amountMinor());
            }
            sql.execute("update transfer_requests set outcome = ? where payment_id = ?", outcome.name(), transfer.paymentId());
            return new TransferResult(transfer, outcome);
        }));
    }
    private <T> T confirmed(T value) { if (reads != null) reads.confirmDurable(); return value; }
    private static Account account(org.jooq.Record r) {
        return new Account(r.get("id", UUID.class), r.get("wallet_id", UUID.class), r.get("owner_id", UUID.class),
            r.get("currency", String.class), r.get("balance_minor", Long.class));
    }
    private static Transfer transfer(org.jooq.Record r) {
        return new Transfer(r.get("payment_id", UUID.class), r.get("debit_account_id", UUID.class), r.get("credit_account_id", UUID.class),
            Currency.getInstance(r.get("currency", String.class)), r.get("amount_minor", Long.class));
    }
    private static TransferResult result(org.jooq.Record r) {
        return new TransferResult(transfer(r), TransferResult.Outcome.valueOf(r.get("outcome", String.class)));
    }
}
