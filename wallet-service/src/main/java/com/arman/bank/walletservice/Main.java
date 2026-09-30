package com.arman.bank.walletservice;
import com.arman.bank.runtime.Database;
import com.arman.bank.runtime.ServiceRuntime;
import com.arman.bank.walletservice.application.WalletService;
import com.arman.bank.walletservice.infrastructure.WalletRoutes;
import com.arman.bank.walletservice.infrastructure.PostgresWallets;
public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        var database = new Database(ServiceRuntime.required("DB_URL"), ServiceRuntime.required("DB_USER"), ServiceRuntime.required("DB_PASSWORD"));
        try {
            var routes = new WalletRoutes(new WalletService(new PostgresWallets(database)), ServiceRuntime.required("INTERNAL_AUTH_KEY"));
            var runtime = ServiceRuntime.start("wallet-service", Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")), database, routes::configure);
            Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "wallet-service-shutdown"));
        } catch (Exception e) { database.close(); throw e; }
    }
}
