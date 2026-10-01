package com.arman.bank.paymentservice;

import com.arman.bank.runtime.*;
import com.arman.bank.paymentservice.application.*;
import com.arman.bank.paymentservice.infrastructure.*;

public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        var peers = new HttpPaymentPeers(ServiceRuntime.required("USER_BASE_URL"), ServiceRuntime.required("WALLET_BASE_URL"),
                ServiceRuntime.required("LEDGER_BASE_URL"), ServiceRuntime.required("INTERNAL_AUTH_KEY"));
        Database db = null;
        ServiceRuntime runtime = null;
        PaymentWorker worker = null;
        try {
            db = new Database(ServiceRuntime.required("DB_URL"), ServiceRuntime.required("DB_USER"), ServiceRuntime.required("DB_PASSWORD"));
            var store = new PostgresPayments(db);
            var routes = new PaymentRoutes(new PaymentService(store, peers), ServiceRuntime.required("INTERNAL_AUTH_KEY"));
            runtime = ServiceRuntime.start("payment-service", Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")), db, routes::configure);
            worker = new PaymentWorker(store, peers);
            worker.start();
            var closingWorker = worker;
            var closingRuntime = runtime;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { closingWorker.close(); }
                finally { try { peers.close(); } finally { closingRuntime.close(); } }
            }, "payment-shutdown"));
        } catch (Exception e) {
            try { if (worker != null) worker.close(); }
            finally { try { peers.close(); } finally { if (runtime != null) runtime.close(); else if (db != null) db.close(); } }
            throw e;
        }
    }
}
