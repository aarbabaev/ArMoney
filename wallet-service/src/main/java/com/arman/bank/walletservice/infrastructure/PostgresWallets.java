package com.arman.bank.walletservice.infrastructure;
import com.arman.bank.walletservice.application.WalletStore;
import com.arman.bank.walletservice.domain.Wallet;
import com.arman.bank.runtime.Database;
import java.util.List;
import java.util.UUID;
public final class PostgresWallets implements WalletStore {
    private final Database database;
    public PostgresWallets(Database database) { this.database = database; }
    public Wallet createOrGet(Wallet wallet) {
        return database.transaction(sql -> {
            // The unique constraint serializes racing inserts. A fresh SELECT at READ COMMITTED sees the winner.
            sql.execute("insert into wallets(id, owner_id, currency, status) values (?, ?, ?, ?) on conflict(owner_id, currency) do nothing",
                wallet.id(), wallet.ownerId(), wallet.currency(), wallet.status());
            return read(sql.fetchOne("select id, owner_id, currency, status from wallets where owner_id = ? and currency = ?",
                wallet.ownerId(), wallet.currency()));
        });
    }
    public List<Wallet> list(UUID owner) {
        return database.transaction(sql -> sql.fetch("select id, owner_id, currency, status from wallets where owner_id = ? order by currency", owner)
            .map(PostgresWallets::read));
    }
    private static Wallet read(org.jooq.Record row) {
        return new Wallet(row.get("id", UUID.class), row.get("owner_id", UUID.class), row.get("currency", String.class), row.get("status", String.class));
    }
}
