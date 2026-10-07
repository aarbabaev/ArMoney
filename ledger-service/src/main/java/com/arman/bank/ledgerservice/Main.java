package com.arman.bank.ledgerservice;
import com.arman.bank.runtime.*;
import com.arman.bank.ledgerservice.application.LedgerService;
import com.arman.bank.ledgerservice.infrastructure.*;
public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        var db = new Database(ServiceRuntime.required("DB_URL"), ServiceRuntime.required("DB_USER"), ServiceRuntime.required("DB_PASSWORD"));
        LedgerReads reads = null;
        try {
            reads = new LedgerReads(db, System.getenv().getOrDefault("LEDGER_READ_DB_URLS", ""), ServiceRuntime.required("DB_USER"), ServiceRuntime.required("DB_PASSWORD"),
                Boolean.parseBoolean(System.getenv().getOrDefault("LEDGER_REQUIRE_SYNC_CONFIRMATION", "false")));
            var ownedReads = reads;
            var routes = new LedgerRoutes(new LedgerService(new PostgresLedger(db, reads)), ServiceRuntime.required("INTERNAL_AUTH_KEY"));
            var runtime = ServiceRuntime.start("ledger-service", Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")), ownedReads::ready,
                () -> { try { ownedReads.close(); } finally { db.close(); } }, routes::configure);
            Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "ledger-shutdown"));
        } catch (Exception e) { if (reads != null) reads.close(); db.close(); throw e; }
    }
}
