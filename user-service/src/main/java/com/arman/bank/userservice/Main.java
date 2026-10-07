package com.arman.bank.userservice;
import com.arman.bank.userservice.infrastructure.ProfileShards;
import com.arman.bank.runtime.ServiceRuntime;
import com.arman.bank.userservice.application.ProfileService;
import com.arman.bank.userservice.infrastructure.ProfileRoutes;
import com.arman.bank.userservice.infrastructure.PostgresProfiles;
public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        var shards = ProfileShards.open(System.getenv());
        try {
            var routes = new ProfileRoutes(new ProfileService(new PostgresProfiles(shards)), ServiceRuntime.required("INTERNAL_AUTH_KEY"));
            var runtime = ServiceRuntime.start("user-service", Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")), shards::ready, shards, routes::configure);
            Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "user-service-shutdown"));
        } catch (Exception e) { shards.close(); throw e; }
    }
}
