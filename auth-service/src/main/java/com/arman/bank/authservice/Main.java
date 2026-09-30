package com.arman.bank.authservice;

import com.arman.bank.authservice.application.AuthService;
import com.arman.bank.authservice.infrastructure.Argon2Passwords;
import com.arman.bank.authservice.infrastructure.AuthRoutes;
import com.arman.bank.authservice.infrastructure.PostgresAuthStore;
import com.arman.bank.runtime.Database;
import com.arman.bank.runtime.ServiceRuntime;
import java.time.Clock;

public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        var database = new Database(ServiceRuntime.required("DB_URL"), ServiceRuntime.required("DB_USER"), ServiceRuntime.required("DB_PASSWORD"));
        try {
            var routes = new AuthRoutes(new AuthService(new PostgresAuthStore(database), new Argon2Passwords(), Clock.systemUTC()),
                    ServiceRuntime.required("INTERNAL_AUTH_KEY"));
            var runtime = ServiceRuntime.start("auth-service", Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")),
                    database, routes::configure);
            Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "auth-shutdown"));
        } catch (Exception e) {
            database.close();
            throw e;
        }
    }
}
