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
    private final MailtrapSmtp smtp;
    public MailtrapDelivery(EmailConfiguration config) {
        this.config=config; owned=new BoundedEmailHttp(); transport=owned::exchange;
        smtp=config.transport.equals("smtp") ? new MailtrapSmtp(config) : null;
    }
    /** Injectable transport for isolated tests, never a configurable provider URL. */
    public MailtrapDelivery(EmailConfiguration config, Transport transport) {
        this.config=config; this.transport=transport; owned=null;
        smtp=config.transport.equals("smtp") ? new MailtrapSmtp(config) : null;
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
        return new Recipient(json.get("email").textValue(),json.get("verified").booleanValue(),displayName(owner));
    }
    private String displayName(UUID owner) throws InterruptedException {
        // A profile greeting is optional: unavailable or untrusted profile data must not stop email.
        try {
            var response=transport.exchange(HttpRequest.newBuilder(config.users.resolve("/v1/users/me"))
                .timeout(Duration.ofSeconds(3)).header("X-Service-Key",config.key)
                .header("X-Identity-Id",owner.toString()).GET().build());
            if (response.status()!=200) return null;
            var json=InternalHttp.JSON.readTree(response.body());
            if (json==null || !json.isObject() || !json.path("identity_id").isTextual()
                || !owner.toString().equalsIgnoreCase(json.path("identity_id").textValue())
                || !json.path("display_name").isTextual()) return null;
            String name=json.path("display_name").textValue().strip();
            if (name.isEmpty() || name.codePointCount(0,name.length())>100
                || name.codePoints().anyMatch(Character::isISOControl)) return null;
            return name;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
          catch (Exception e) { return null; }
    }
    @Override public Result send(EmailOutbox.Claim claim,String recipient) throws Exception {
        if (!EmailConfiguration.validEmail(recipient)) return Result.PERMANENT_FAILURE;
        var content=PaymentEmail.render(claim);
        if (smtp!=null) return smtp.send(recipient,content.subject(),content.text(),content.html());
        var payload=Map.of("from",Map.of("email",config.fromEmail,"name",config.fromName),
            "to",List.of(Map.of("email",recipient)), "subject",content.subject(),
            "text",content.text(), "html",content.html());
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
