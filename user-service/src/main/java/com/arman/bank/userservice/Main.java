package com.arman.bank.userservice;
import com.arman.bank.runtime.Database;
import com.arman.bank.runtime.ServiceRuntime;
import com.arman.bank.userservice.application.ProfileService;
import com.arman.bank.userservice.infrastructure.ProfileRoutes;
import com.arman.bank.userservice.infrastructure.PostgresProfiles;
public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        var database = new Database(ServiceRuntime.required("DB_URL"), ServiceRuntime.required("DB_USER"), ServiceRuntime.required("DB_PASSWORD"));
        try {
            var routes = new ProfileRoutes(new ProfileService(new PostgresProfiles(database)), ServiceRuntime.required("INTERNAL_AUTH_KEY"));
            var runtime = ServiceRuntime.start("user-service", Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")), database, routes::configure);
            Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "user-service-shutdown"));
        } catch (Exception e) { database.close(); throw e; }
    }
}
