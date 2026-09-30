package com.arman.bank.walletservice.infrastructure;
import com.arman.bank.walletservice.application.WalletStore;
import com.arman.bank.walletservice.application.ProvisioningStore;
import com.arman.bank.walletservice.domain.Wallet;
import com.arman.bank.runtime.Database;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public final class PostgresWallets implements WalletStore, ProvisioningStore {
    private final Database database;
    public PostgresWallets(Database database) { this.database = database; }
    public Wallet createOrGet(Wallet wallet) {
        return transaction(sql -> {
            // The unique constraint serializes racing inserts. A fresh SELECT sees the winner.
            sql.execute("insert into wallets(id, owner_id, currency, status) values (?, ?, ?, ?) on conflict(owner_id, currency) do nothing",
                wallet.id(), wallet.ownerId(), wallet.currency(), wallet.status());
            return read(sql.fetchOne("select * from wallets where owner_id = ? and currency = ?",
                wallet.ownerId(), wallet.currency()));
        });
    }
    public List<Wallet> list(UUID owner) {
        return transaction(sql -> sql.fetch("select * from wallets where owner_id = ? order by currency", owner)
            .map(PostgresWallets::read));
    }
    public Optional<Claim> claim() {
        return transaction(sql -> {
            UUID token = UUID.randomUUID();
            var row = sql.fetchOne("""
                with due as (
                    select id from wallets where status = 'ACTIVE' and provisioning_status = 'PENDING'
                        and next_provisioning_at <= current_timestamp
                    order by next_provisioning_at, id for update skip locked limit 1
                )
                update wallets w set provisioning_token = ?, next_provisioning_at = current_timestamp + interval '30 seconds',
                    provisioning_attempts = least(provisioning_attempts, 29) + 1
                from due where w.id = due.id returning w.*
                """, token);
            return row == null ? Optional.empty() : Optional.of(new Claim(read(row), token, row.get("provisioning_attempts", Integer.class)));
        });
    }
    public boolean complete(Claim claim, UUID account) {
        java.util.Objects.requireNonNull(account);
        return transaction(sql -> sql.execute("""
            update wallets set provisioning_status = 'READY', ledger_account_id = ?, provisioning_token = null
            where id = ? and provisioning_token = ? and status = 'ACTIVE' and provisioning_status = 'PENDING'
            """, account, claim.wallet().id(), claim.token()) == 1);
    }
    public void retry(Claim claim, int delaySeconds) {
        if (delaySeconds < 1 || delaySeconds > 60) throw new IllegalArgumentException("Invalid retry delay");
        transaction(sql -> sql.execute("""
            update wallets set provisioning_token = null, next_provisioning_at = current_timestamp + (? * interval '1 second')
            where id = ? and provisioning_token = ? and status = 'ACTIVE' and provisioning_status = 'PENDING'
            """, delaySeconds, claim.wallet().id(), claim.token()));
    }
    private <T> T transaction(java.util.function.Function<org.jooq.DSLContext, T> operation) {
        return database.transaction(sql -> {
            // Bound lock waits and execution even while shutting down a worker.
            sql.execute("set local lock_timeout = '2s'");
            sql.execute("set local statement_timeout = '5s'");
            return operation.apply(sql);
        });
    }
    private static Wallet read(org.jooq.Record row) {
        return new Wallet(row.get("id", UUID.class), row.get("owner_id", UUID.class), row.get("currency", String.class),
            row.get("status", String.class), row.get("provisioning_status", String.class), row.get("ledger_account_id", UUID.class));
    }
}
