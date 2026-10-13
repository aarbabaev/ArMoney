package com.arman.bank.paymentservice.infrastructure;

import com.arman.bank.paymentservice.application.*;
import com.arman.bank.paymentservice.domain.*;
import com.arman.bank.runtime.InternalHttp;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.config.JavalinConfig;
import io.javalin.http.*;
import java.util.*;

public final class PaymentRoutes {
    private final PaymentService service;
    private final String key;
    private final PageCursor cursors;
    public PaymentRoutes(PaymentService service, String key) { this.service = service; this.key = key; this.cursors = new PageCursor(key); }
    public void configure(JavalinConfig config) {
        InternalHttp.configure(config, key);
        config.routes.before(ctx -> {
            if (ctx.path().startsWith("/v1/") && (Collections.list(ctx.req().getHeaders("X-Service-Key")).size() != 1
                    || Collections.list(ctx.req().getHeaders("X-Identity-Id")).size() != 1)) throw new UnauthorizedResponse();
        });
        config.routes.exception(PaymentFailure.class, (e, ctx) -> InternalHttp.reply(ctx, e.status(), Map.of("error", e.code())));
        config.routes.post("/v1/payments", ctx -> {
            if (Collections.list(ctx.req().getHeaders("Idempotency-Key")).size() != 1) throw new BadRequestResponse();
            var payment = service.submit(InternalHttp.owner(ctx), ctx.header("Idempotency-Key"), request(ctx));
            InternalHttp.reply(ctx, payment.status().equals("PENDING") ? 202 : 200, view(payment));
        });
        config.routes.get("/v1/payments", ctx -> InternalHttp.reply(ctx, 200,
                Map.of("payments", service.history(InternalHttp.owner(ctx)).stream().map(PaymentRoutes::view).toList())));
        config.routes.get("/v1/payments/page", ctx -> page(ctx, false));
        config.routes.get("/v1/notifications/page", ctx -> page(ctx, true));
        config.routes.get("/v1/payments/{id}", ctx -> InternalHttp.reply(ctx, 200,
                view(service.payment(InternalHttp.owner(ctx), uuid(ctx.pathParam("id"))))));
        config.routes.get("/v1/notifications", ctx -> InternalHttp.reply(ctx, 200,
                Map.of("notifications", service.notifications(InternalHttp.owner(ctx)).stream().map(PaymentRoutes::view).toList())));
        config.routes.post("/v1/notifications/{id}/read", ctx -> {
            if (!ctx.body().isEmpty()) throw new BadRequestResponse();
            InternalHttp.reply(ctx, 200, view(service.readNotification(InternalHttp.owner(ctx), uuid(ctx.pathParam("id")))));
        });
    }
    private void page(Context ctx, boolean notifications) {
        UUID owner = InternalHttp.owner(ctx);
        String query = ctx.queryString();
        String suppliedLimit = null, cursor = null;
        if (query != null) {
            if (query.length() > 1024) throw PageCursor.invalid();
            for (String pair : query.split("&", -1)) {
                if (pair.startsWith("limit=") && suppliedLimit == null && pair.substring(6).matches("(?:[1-9][0-9]?|100)")) {
                    suppliedLimit = pair.substring(6);
                } else if (pair.startsWith("cursor=") && cursor == null && pair.substring(7).matches("[A-Za-z0-9_-]{1,512}")) {
                    cursor = pair.substring(7);
                } else throw PageCursor.invalid();
            }
        }
        int limit = suppliedLimit == null ? 50 : Integer.parseInt(suppliedLimit);
        PagePosition before = cursor == null ? null : cursors.decode(cursor, owner, notifications);
        var result = new LinkedHashMap<String, Object>();
        PagePosition next = null;
        if (notifications) {
            var rows = service.notificationsPage(owner, before, limit + 1);
            var items = rows.subList(0, Math.min(limit, rows.size()));
            result.put("notifications", items.stream().map(PaymentRoutes::view).toList());
            if (rows.size() > limit) { var last = items.getLast(); next = new PagePosition(last.createdAt(), last.id()); }
        } else {
            var rows = service.historyPage(owner, before, limit + 1);
            var items = rows.subList(0, Math.min(limit, rows.size()));
            result.put("payments", items.stream().map(PaymentRoutes::view).toList());
            if (rows.size() > limit) { var last = items.getLast(); next = new PagePosition(last.createdAt(), last.id()); }
        }
        result.put("next_cursor", next == null ? null : cursors.encode(owner, notifications, next));
        InternalHttp.reply(ctx, 200, result);
    }
    private static PaymentRequest request(Context ctx) {
        String type = ctx.contentType();
        if (type == null || !type.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) throw new BadRequestResponse();
        try {
            JsonNode json = InternalHttp.JSON.readTree(ctx.body());
            if (json == null || !json.isObject() || json.size() != 5) throw new BadRequestResponse();
            for (String field : List.of("source_wallet_id", "recipient_id", "recipient_phone", "currency"))
                if (!json.path(field).isTextual()) throw new BadRequestResponse();
            if (!json.path("amount_minor").isIntegralNumber() || !json.path("amount_minor").canConvertToLong()) throw new BadRequestResponse();
            return new PaymentRequest(uuid(json.path("source_wallet_id").textValue()), uuid(json.path("recipient_id").textValue()),
                    json.path("recipient_phone").textValue(), json.path("currency").textValue(), json.path("amount_minor").longValue());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new BadRequestResponse(); }
    }
    private static UUID uuid(String text) {
        var id = UUID.fromString(text);
        if (!id.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException("Invalid UUID");
        return id;
    }
    private static Map<String, Object> view(Payment p) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", p.id().toString()); result.put("requester_id", p.requesterId().toString());
        result.put("recipient_id", p.request().recipientId().toString());
        result.put("source_wallet_id", p.request().sourceWalletId().toString()); result.put("destination_wallet_id", p.destinationWalletId().toString());
        result.put("recipient_phone", p.request().recipientPhone()); result.put("currency", p.request().currency()); result.put("amount_minor", p.request().amountMinor());
        result.put("status", p.status()); result.put("rejection_reason", p.rejectionReason());
        result.put("created_at", p.createdAt().toString()); result.put("updated_at", p.updatedAt().toString());
        return result;
    }
    private static Map<String, Object> view(Notification n) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", n.id().toString()); result.put("payment_id", n.paymentId().toString()); result.put("type", n.type());
        result.put("currency", n.currency()); result.put("amount_minor", n.amountMinor()); result.put("created_at", n.createdAt().toString());
        result.put("read_at", n.readAt() == null ? null : n.readAt().toString());
        return result;
    }
}
