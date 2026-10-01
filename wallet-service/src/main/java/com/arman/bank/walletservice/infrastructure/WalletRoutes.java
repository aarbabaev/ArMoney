package com.arman.bank.walletservice.infrastructure;
import com.arman.bank.runtime.InternalHttp;
import com.arman.bank.walletservice.application.WalletService;
import com.arman.bank.walletservice.application.LedgerBalances;
import com.arman.bank.walletservice.domain.Wallet;
import io.javalin.config.JavalinConfig;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.ServiceUnavailableResponse;
public final class WalletRoutes {
    private final WalletService service;
    private final String key;
    private final LedgerBalances ledger;
    public WalletRoutes(WalletService service, String key) { this(service, key, wallet -> { throw new java.io.IOException("Ledger unavailable"); }); }
    public WalletRoutes(WalletService service, String key, LedgerBalances ledger) { this.service = service; this.key = key; this.ledger = ledger; }
    public void configure(JavalinConfig config) {
        InternalHttp.configure(config, key);
        config.routes.post("/v1/wallets", ctx -> {
            var wallet = service.open(InternalHttp.owner(ctx), InternalHttp.body(ctx, "currency").get("currency").asText());
            int status = "ACTIVE".equals(wallet.status()) && "PENDING".equals(wallet.provisioningStatus()) ? 202 : 200;
            InternalHttp.reply(ctx, status, view(wallet));
        });
        config.routes.get("/v1/wallets", ctx -> InternalHttp.reply(ctx, 200,
            Map.of("wallets", service.list(InternalHttp.owner(ctx)).stream().map(WalletRoutes::view).toList())));
        config.routes.get("/v1/internal/wallets/{id}", ctx -> InternalHttp.reply(ctx, 200,
            view(service.find(id(ctx.pathParam("id"))).orElseThrow(NotFoundResponse::new))));
        config.routes.get("/v1/internal/wallets/by-owner/{owner}/currency/{currency}", ctx -> InternalHttp.reply(ctx, 200,
            view(service.find(id(ctx.pathParam("owner")), ctx.pathParam("currency")).orElseThrow(NotFoundResponse::new))));
        config.routes.get("/v1/wallets/{id}/balance", ctx -> {
            var wallet = service.find(id(ctx.pathParam("id"))).filter(w -> w.ownerId().equals(InternalHttp.owner(ctx))).orElseThrow(NotFoundResponse::new);
            if (!"ACTIVE".equals(wallet.status()) || !"READY".equals(wallet.provisioningStatus())) throw new ConflictResponse();
            final long amount;
            try { amount = ledger.balance(wallet); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ServiceUnavailableResponse(); }
            catch (Exception e) { throw new ServiceUnavailableResponse(); }
            InternalHttp.reply(ctx, 200, Map.of("wallet_id", wallet.id().toString(), "currency", wallet.currency(), "balance_minor", amount));
        });
    }
    private static UUID id(String value) {
        var id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Canonical UUID required");
        return id;
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
