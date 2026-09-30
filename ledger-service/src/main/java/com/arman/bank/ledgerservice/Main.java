package com.arman.bank.ledgerservice;
import com.arman.bank.runtime.*;
import com.arman.bank.ledgerservice.application.LedgerService;
import com.arman.bank.ledgerservice.infrastructure.*;
public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        var db = new Database(ServiceRuntime.required("DB_URL"), ServiceRuntime.required("DB_USER"), ServiceRuntime.required("DB_PASSWORD"));
        try {
            var routes = new LedgerRoutes(new LedgerService(new PostgresLedger(db)), ServiceRuntime.required("INTERNAL_AUTH_KEY"));
            var runtime = ServiceRuntime.start("ledger-service", Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")), db, routes::configure);
            Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "ledger-shutdown"));
        } catch (Exception e) { db.close(); throw e; }
    }
}
