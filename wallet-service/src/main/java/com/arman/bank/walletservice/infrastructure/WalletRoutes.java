package com.arman.bank.walletservice.infrastructure;
import com.arman.bank.runtime.InternalHttp;
import com.arman.bank.walletservice.application.WalletService;
import com.arman.bank.walletservice.domain.Wallet;
import io.javalin.config.JavalinConfig;
import java.util.Map;
public final class WalletRoutes {
    private final WalletService service;
    private final String key;
    public WalletRoutes(WalletService service, String key) { this.service = service; this.key = key; }
    public void configure(JavalinConfig config) {
        InternalHttp.configure(config, key);
        config.routes.post("/v1/wallets", ctx -> InternalHttp.reply(ctx, 200,
            view(service.open(InternalHttp.owner(ctx), InternalHttp.body(ctx, "currency").get("currency").asText()))));
        config.routes.get("/v1/wallets", ctx -> InternalHttp.reply(ctx, 200,
            Map.of("wallets", service.list(InternalHttp.owner(ctx)).stream().map(WalletRoutes::view).toList())));
    }
    private static Map<String, String> view(Wallet w) {
        return Map.of("id", w.id().toString(), "owner_id", w.ownerId().toString(), "currency", w.currency(), "status", w.status());
    }
}
