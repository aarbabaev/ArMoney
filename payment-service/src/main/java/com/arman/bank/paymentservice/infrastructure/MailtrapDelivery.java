package com.arman.bank.paymentservice.infrastructure;
import com.arman.bank.paymentservice.application.*;
import com.arman.bank.runtime.InternalHttp;
import java.io.IOException;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

public final class MailtrapDelivery implements EmailDelivery, AutoCloseable {
    public record Response(int status, byte[] body) {}
    @FunctionalInterface public interface Transport { Response exchange(HttpRequest request) throws Exception; }
    private final EmailConfiguration config;
    private final Transport transport;
    private final BoundedEmailHttp owned;
    public MailtrapDelivery(EmailConfiguration config) {
        this.config=config; owned=new BoundedEmailHttp(); transport=owned::exchange;
    }
    /** Injectable transport for isolated tests, never a configurable provider URL. */
    public MailtrapDelivery(EmailConfiguration config, Transport transport) {
        this.config=config; this.transport=transport; owned=null;
    }
    @Override public Recipient recipient(UUID owner) throws Exception {
        var response=transport.exchange(HttpRequest.newBuilder(config.auth.resolve("/v1/internal/identities/"+owner+"/email"))
            .timeout(Duration.ofSeconds(5)).header("X-Service-Key",config.key).GET().build());
        if (response.status()==404) return new Recipient(null,false);
        if (response.status()!=200) throw new IOException("Identity email unavailable");
        var json=InternalHttp.JSON.readTree(response.body());
        if (json==null || !json.isObject() || !json.has("email") || !json.path("verified").isBoolean())
            throw new IOException("Invalid identity email response");
        if (json.get("email").isNull()) return new Recipient(null,json.get("verified").booleanValue());
        if (!json.get("email").isTextual() || !EmailConfiguration.validEmail(json.get("email").textValue()))
            throw new IOException("Invalid identity email response");
        return new Recipient(json.get("email").textValue(),json.get("verified").booleanValue());
    }
    @Override public Result send(EmailOutbox.Claim claim,String recipient) throws Exception {
        if (!EmailConfiguration.validEmail(recipient)) return Result.PERMANENT_FAILURE;
        String action=switch(claim.type()) {
            case "PAYMENT_COMPLETED" -> "Payment completed";
            case "PAYMENT_REJECTED" -> "Payment rejected";
            case "PAYMENT_RECEIVED" -> "Payment received";
            default -> throw new IllegalArgumentException("Invalid email event");
        };
        String amount=claim.amountMinor()/100+"."+String.format(Locale.ROOT,"%02d",claim.amountMinor()%100);
        var payload=Map.of("from",Map.of("email",config.fromEmail,"name",config.fromName),
            "to",List.of(Map.of("email",recipient)), "subject","ArMoney: "+action,
            "text",action+". Amount: AED "+amount+". Payment ID: "+claim.paymentId()+". Event ID: "+claim.id()+".");
        var builder=HttpRequest.newBuilder(config.endpoint).timeout(Duration.ofSeconds(5))
            .header("Content-Type","application/json");
        if (config.mode.equals("sandbox")) builder.header("Api-Token",config.token);
        else builder.header("Authorization","Bearer "+config.token);
        var response=transport.exchange(builder.POST(HttpRequest.BodyPublishers.ofByteArray(InternalHttp.JSON.writeValueAsBytes(payload))).build());
        if (response.status()==429) return Result.RATE_LIMITED;
        if (response.status()==408 || response.status()>=500) return Result.TRANSIENT_FAILURE;
        if (response.status()!=200) return Result.PERMANENT_FAILURE;
        var json=InternalHttp.JSON.readTree(response.body());
        return json!=null && json.path("success").isBoolean() && json.path("success").booleanValue()
            ? Result.ACCEPTED : Result.TRANSIENT_FAILURE;
    }
    @Override public void close() { if (owned!=null) owned.close(); }
}
