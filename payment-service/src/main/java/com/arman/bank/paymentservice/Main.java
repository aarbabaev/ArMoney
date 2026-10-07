package com.arman.bank.paymentservice;

import com.arman.bank.runtime.*;
import com.arman.bank.paymentservice.application.*;
import com.arman.bank.paymentservice.infrastructure.*;

public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        var emailConfig = new EmailConfiguration(System.getenv());
        var peers = new HttpPaymentPeers(ServiceRuntime.required("USER_BASE_URL"), ServiceRuntime.required("WALLET_BASE_URL"),
                ServiceRuntime.required("LEDGER_BASE_URL"), ServiceRuntime.required("INTERNAL_AUTH_KEY"));
        MailtrapDelivery delivery = null;
        EmailWorker emailWorker = null;
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
            if (emailConfig.enabled) {
                delivery = new MailtrapDelivery(emailConfig);
                emailWorker = new EmailWorker(new PostgresEmailOutbox(db), delivery, emailConfig.mode);
                emailWorker.start();
            }
            var closingEmailWorker = emailWorker;
            var closingDelivery = delivery;
            var closingWorker = worker;
            var closingRuntime = runtime;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    try { if (closingEmailWorker != null) closingEmailWorker.close(); }
                    finally { try { if (closingDelivery != null) closingDelivery.close(); } finally { closingWorker.close(); } }
                }
                finally { try { peers.close(); } finally { closingRuntime.close(); } }
            }, "payment-shutdown"));
        } catch (Exception e) {
            try {
                try { if (emailWorker != null) emailWorker.close(); }
                finally { try { if (delivery != null) delivery.close(); } finally { if (worker != null) worker.close(); } }
            }
            finally { try { peers.close(); } finally { if (runtime != null) runtime.close(); else if (db != null) db.close(); } }
            throw e;
        }
    }
}
