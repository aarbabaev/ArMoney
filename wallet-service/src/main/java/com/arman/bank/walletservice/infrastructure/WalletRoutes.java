package com.arman.bank.walletservice.infrastructure;
import com.arman.bank.runtime.InternalHttp;
import com.arman.bank.walletservice.application.WalletService;
import com.arman.bank.walletservice.domain.Wallet;
import io.javalin.config.JavalinConfig;
import java.util.Map;
import java.util.LinkedHashMap;
public final class WalletRoutes {
    private final WalletService service;
    private final String key;
    public WalletRoutes(WalletService service, String key) { this.service = service; this.key = key; }
    public void configure(JavalinConfig config) {
        InternalHttp.configure(config, key);
        config.routes.post("/v1/wallets", ctx -> {
            var wallet = service.open(InternalHttp.owner(ctx), InternalHttp.body(ctx, "currency").get("currency").asText());
            int status = "ACTIVE".equals(wallet.status()) && "PENDING".equals(wallet.provisioningStatus()) ? 202 : 200;
            InternalHttp.reply(ctx, status, view(wallet));
        });
        config.routes.get("/v1/wallets", ctx -> InternalHttp.reply(ctx, 200,
            Map.of("wallets", service.list(InternalHttp.owner(ctx)).stream().map(WalletRoutes::view).toList())));
    }
    private static Map<String, Object> view(Wallet w) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", w.id().toString());
        view.put("owner_id", w.ownerId().toString());
        view.put("currency", w.currency());
        view.put("status", w.status());
        view.put("provisioning_status", w.provisioningStatus());
        view.put("ledger_account_id", w.ledgerAccountId() == null ? null : w.ledgerAccountId().toString());
        return view;
    }
}
