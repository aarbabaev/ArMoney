package com.arman.bank.ledgerservice.infrastructure;
import com.arman.bank.ledgerservice.application.*;
import com.arman.bank.ledgerservice.domain.*;
import com.arman.bank.runtime.InternalHttp;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.config.JavalinConfig;
import io.javalin.http.*;
import java.util.*;

public final class LedgerRoutes {
    private final LedgerService service;
    private final String key;
    public LedgerRoutes(LedgerService service, String key) { this.service = service; this.key = key; }
    public void configure(JavalinConfig config) {
        InternalHttp.configure(config, key);
        config.routes.exception(LedgerConflict.class, (e, ctx) -> InternalHttp.reply(ctx, 409, Map.of("error", "idempotency_conflict")));
        config.routes.post("/v1/ledger/accounts", ctx -> {
            var body = InternalHttp.body(ctx, "wallet_id", "currency");
            InternalHttp.reply(ctx, 200, view(service.open(InternalHttp.owner(ctx), uuid(body.get("wallet_id").asText()), body.get("currency").asText())));
        });
        config.routes.get("/v1/ledger/accounts/{id}", ctx -> InternalHttp.reply(ctx, 200,
            view(service.account(InternalHttp.owner(ctx), uuid(ctx.pathParam("id"))).orElseThrow(NotFoundResponse::new))));
        config.routes.post("/v1/ledger/transfers", ctx -> {
            var b = transferBody(ctx);
            var transfer = new Transfer(uuid(b.get("payment_id").asText()), uuid(b.get("debit_account_id").asText()),
                uuid(b.get("credit_account_id").asText()), Currency.getInstance(b.get("currency").asText()), b.get("amount_minor").longValue());
            var result = service.post(InternalHttp.owner(ctx), transfer);
            InternalHttp.reply(ctx, result.outcome() == TransferResult.Outcome.POSTED ? 200 : 409, view(result));
        });
        config.routes.get("/v1/ledger/transfers/{id}", ctx -> InternalHttp.reply(ctx, 200,
            view(service.result(InternalHttp.owner(ctx), uuid(ctx.pathParam("id"))).orElseThrow(NotFoundResponse::new))));
    }
    private static UUID uuid(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Invalid UUID");
        return parsed;
    }
    private static JsonNode transferBody(Context ctx) {
        String type = ctx.contentType();
        if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) throw new BadRequestResponse();
        try {
            var b = InternalHttp.JSON.readTree(ctx.body());
            if (b == null || !b.isObject() || b.size() != 5) throw new BadRequestResponse();
            for (String name : List.of("payment_id", "debit_account_id", "credit_account_id", "currency"))
                if (!b.hasNonNull(name) || !b.get(name).isTextual()) throw new BadRequestResponse();
            var amount = b.get("amount_minor");
            if (amount == null || !amount.isIntegralNumber() || !amount.canConvertToLong()) throw new BadRequestResponse();
            return b;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new BadRequestResponse(); }
    }
    private static Map<String, Object> view(Account a) {
        return Map.of("id", a.id().toString(), "wallet_id", a.walletId().toString(), "owner_id", a.ownerId().toString(),
            "currency", a.currency(), "balance_minor", a.balanceMinor());
    }
    private static Map<String, Object> view(TransferResult r) {
        var t = r.transfer();
        return Map.of("payment_id", t.paymentId().toString(), "debit_account_id", t.debitAccountId().toString(),
            "credit_account_id", t.creditAccountId().toString(), "currency", t.currency().getCurrencyCode(),
            "amount_minor", t.amountMinor(), "outcome", r.outcome().name());
    }
}
