package com.arman.bank.appgateway;

import com.arman.bank.runtime.ServiceRuntime;
import java.net.URI;
import java.time.Clock;

public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        var proxy = new AuthProxy(URI.create(ServiceRuntime.required("AUTH_BASE_URL")),
                ServiceRuntime.required("INTERNAL_AUTH_KEY"), Clock.systemUTC());
        try {
            var runtime = ServiceRuntime.start("app-gateway", Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")),
                    null, proxy::configure);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { runtime.close(); } finally { proxy.close(); }
            }, "gateway-shutdown"));
        } catch (Exception e) { proxy.close(); throw e; }
    }
}
