package com.arman.bank.appgateway;
import com.arman.bank.runtime.ServiceRuntime;
import java.net.URI;
import java.time.Clock;
public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        var auth = URI.create(ServiceRuntime.required("AUTH_BASE_URL"));
        var users = URI.create(ServiceRuntime.required("USER_BASE_URL"));
        var wallets = URI.create(ServiceRuntime.required("WALLET_BASE_URL"));
        var key = ServiceRuntime.required("INTERNAL_AUTH_KEY");
        var proxy = new AuthProxy(auth, key, Clock.systemUTC());
        try {
            var protectedProxy = new ProtectedProxy(auth, users, wallets, key);
            try {
                var runtime = ServiceRuntime.start("app-gateway", Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")),
                    null, config -> { proxy.configure(config); protectedProxy.configure(config); });
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try { runtime.close(); } finally { try { proxy.close(); } finally { protectedProxy.close(); } }
                }, "gateway-shutdown"));
            } catch (Exception e) { protectedProxy.close(); throw e; }
        } catch (Exception e) { proxy.close(); throw e; }
    }
}
