package com.arman.bank.ledgerservice.infrastructure;

import com.arman.bank.ledgerservice.application.LedgerConflict;
import com.arman.bank.runtime.Database;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Explicit operator-only synthetic local baseline, never registered as an HTTP route. */
public final class LocalDemoFunding {
    public static final UUID CLEARING_ID = UUID.fromString("76ce90fa-daa2-48f2-842d-efbdfbfa88bc");
    public static final UUID CLEARING_WALLET_ID = UUID.fromString("e368ae6e-bf19-425e-b694-b71eb4dadf92");
    private final Database db;

    public LocalDemoFunding(Database db, Map<String, String> environment) {
        requireLocalOptIn(environment);
        this.db = Objects.requireNonNull(db);
    }

    public static void requireLocalOptIn(Map<String, String> environment) {
        if (!"local".equals(environment.get("APP_ENV")) ||
            !"I_UNDERSTAND_SYNTHETIC_FUNDS".equals(environment.get("ARMONEY_LOCAL_DEMO_FUNDING"))) {
            throw new IllegalStateException("Explicit local synthetic funding opt-in required");
        }
    }

    public record Command(UUID fundingId, UUID ownerId, UUID walletId, UUID accountId, long amountFils) {
        public Command {
            Objects.requireNonNull(fundingId);
            Objects.requireNonNull(ownerId);
            Objects.requireNonNull(walletId);
            Objects.requireNonNull(accountId);
            if (amountFils < 100000 || amountFils > 500000)
                throw new IllegalArgumentException("Baseline amount must be 100000..500000 integer fils");
            if (CLEARING_ID.equals(accountId) || CLEARING_WALLET_ID.equals(walletId))
                throw new IllegalArgumentException("Customer target required");
        }
    }

    public String fund(Command command) {
        return db.transaction(sql -> {
            // Reserve before account locks, matching the ordinary transfer lock order.
            int inserted = sql.execute("""
                insert into transfer_requests(payment_id, requester_id, debit_account_id, credit_account_id, currency, amount_minor, outcome)
                values (?, ?, ?, ?, 'AED', ?, 'PENDING') on conflict(payment_id) do nothing
                """, command.fundingId(), command.ownerId(), CLEARING_ID, command.accountId(), command.amountFils());
            var request = sql.fetchOne("select * from transfer_requests where payment_id = ?", command.fundingId());
            if (!command.ownerId().equals(request.get("requester_id", UUID.class)) ||
                !CLEARING_ID.equals(request.get("debit_account_id", UUID.class)) ||
                !command.accountId().equals(request.get("credit_account_id", UUID.class)) ||
                !"AED".equals(request.get("currency", String.class)) ||
                command.amountFils() != request.get("amount_minor", Long.class)) throw new LedgerConflict();

            sql.execute("""
                insert into accounts(id, wallet_id, currency, account_kind)
                values (?, ?, 'AED', 'CLEARING') on conflict do nothing
                """, CLEARING_ID, CLEARING_WALLET_ID);
            var locked = sql.fetch("select * from accounts where id in (?, ?) order by id for update", CLEARING_ID, command.accountId());
            var clearing = locked.stream().filter(r -> CLEARING_ID.equals(r.get("id", UUID.class))).findFirst().orElse(null);
            var target = locked.stream().filter(r -> command.accountId().equals(r.get("id", UUID.class))).findFirst().orElse(null);
            if (clearing == null || !CLEARING_WALLET_ID.equals(clearing.get("wallet_id", UUID.class)) ||
                !"CLEARING".equals(clearing.get("account_kind", String.class)) ||
                clearing.get("owner_id") != null || !"AED".equals(clearing.get("currency", String.class))) throw new LedgerConflict();
            if (target == null || !command.ownerId().equals(target.get("owner_id", UUID.class)) ||
                !command.walletId().equals(target.get("wallet_id", UUID.class)) ||
                !"CUSTOMER".equals(target.get("account_kind", String.class)) ||
                !"AED".equals(target.get("currency", String.class))) throw new LedgerConflict();
            if (inserted == 0) {
                if (!"POSTED".equals(request.get("outcome", String.class))) throw new LedgerConflict();
                return "POSTED";
            }
            // A new ID must never refill an account that has already received or spent money.
            if (target.get("balance_minor", Long.class) != 0 ||
                sql.fetchExists(sql.selectOne().from("postings").where("account_id = ?", command.accountId())))
                throw new LedgerConflict();
            Math.subtractExact(clearing.get("balance_minor", Long.class), command.amountFils());
            Math.addExact(target.get("balance_minor", Long.class), command.amountFils());
            sql.execute("""
                insert into transfers(payment_id, debit_account_id, credit_account_id, currency, amount_minor)
                values (?, ?, ?, 'AED', ?)
                """, command.fundingId(), CLEARING_ID, command.accountId(), command.amountFils());
            sql.execute("update transfer_requests set outcome = 'POSTED' where payment_id = ?", command.fundingId());
            return "POSTED";
        });
    }
}
