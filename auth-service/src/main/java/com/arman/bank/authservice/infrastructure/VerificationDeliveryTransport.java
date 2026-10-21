package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.NotificationDelivery.Result;
import com.arman.bank.authservice.application.VerificationService;
import com.arman.bank.authservice.domain.Verification.Purpose;
import com.arman.bank.authservice.infrastructure.PostgresVerificationStore.DeliveryLease;
import com.fasterxml.jackson.databind.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Existing sandbox transports only; no protocol/body logging or redirect following. */
public final class VerificationDeliveryTransport implements VerificationDeliveryWorker.Sender {
    /** Closed wire purposes; never infer migration delivery as registration. */
    enum SmsPurpose { REGISTRATION_PHONE, LEGACY_MIGRATION_PHONE }
    @FunctionalInterface interface MailSender { Result send(String recipient, String subject, String text, String html) throws InterruptedException; }
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final MailSender mail;
    private final HttpClient client;
    private final URI endpoint;
    private final String smsToken;
    private volatile boolean closed;
    public VerificationDeliveryTransport(EmailConfiguration email, URI base, String smsToken) {
        this(new MailtrapSmtp(email)::send, VerificationDeliveryConfiguration.smsOrigin(base.toString()), smsToken, false);
    }
    /** Loopback seam is package-private and cannot be selected through environment configuration. */
    VerificationDeliveryTransport(MailSender mail, URI base, String smsToken, boolean loopbackFixture) {
        if (loopbackFixture) {
            if (!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost()) || base.getPort() < 1
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                    || !(base.getPath().isEmpty() || "/".equals(base.getPath()))) throw new IllegalArgumentException("Invalid loopback fixture");
        } else VerificationDeliveryConfiguration.smsOrigin(base.toString());
        if (smsToken == null || !smsToken.matches("[A-Za-z0-9_-]{32,128}")) throw new IllegalArgumentException("Invalid SMS sender token");
        this.mail = Objects.requireNonNull(mail); this.smsToken = smsToken; endpoint = base.resolve("/api/messages");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public boolean send(DeliveryLease lease, VerificationCrypto.DeliveryPayload payload) throws InterruptedException {
        if (closed || lease == null || payload == null || !payload.code().matches("[0-9]{6}")) return false;
        String text = "Your ArMoney registration verification code is " + payload.code() + ". It expires in 5 minutes. Do not share this code.";
        if (lease.purpose() == Purpose.REGISTRATION_EMAIL) {
            var email = VerificationEmail.render(payload.code());
            return sendEmail(payload.destination(), email.subject(), email.text(), email.html());
        }
        if (lease.purpose() != Purpose.REGISTRATION_PHONE) return false;
        return sendSms(lease.deliveryId(), payload.destination(), text, SmsPurpose.REGISTRATION_PHONE);
    }
    /** Borrowed by migration delivery; SMTP acceptance is not possession or exactly-once proof. */
    boolean sendEmail(String destination, String subject, String text, String html) throws InterruptedException {
        if (closed || !EmailConfiguration.validEmail(destination) || subject == null || text == null) return false;
        return mail.send(destination, subject, text, html) == Result.ACCEPTED;
    }
    /** No registration lease/version is required by this purpose-explicit transport port. */
    boolean sendSms(UUID deliveryId, String destination, String text, SmsPurpose purpose) throws InterruptedException {
        if (closed || deliveryId == null || purpose == null || !VerificationService.canonicalPhone(destination)
                || text == null || text.isBlank() || text.length() > 2048) return false;
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        byte[] requestBody = null, responseBody = null;
        try {
            requestBody = JSON.writeValueAsBytes(Map.of("destination", destination, "text", text, "purpose", purpose.name()));
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(4))
                .header("Authorization", "Bearer " + smsToken).header("Idempotency-Key", deliveryId.toString())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(requestBody)).build();
            pending = client.sendAsync(request, info -> new BoundedBody());
            var response = pending.get(4, TimeUnit.SECONDS);
            responseBody = response.body();
            if ((response.statusCode() != 200 && response.statusCode() != 201)
                    || !response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip().equalsIgnoreCase("application/json")) return false;
            JsonNode ack = JSON.readTree(responseBody);
            if (ack == null || !ack.isObject() || ack.size() != 3 || !ack.has("id") || !ack.has("createdAt") || !ack.has("replay")) return false;
            JsonNode id = ack.get("id"), created = ack.get("createdAt"), replay = ack.get("replay");
            if (!id.isTextual() || !id.textValue().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                    || !created.isIntegralNumber() || !created.canConvertToLong() || created.longValue() < 0 || !replay.isBoolean()) return false;
            UUID.fromString(id.textValue());
            return replay.booleanValue() == (response.statusCode() == 200);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
        catch (Exception unavailable) { return false; }
        finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            if (requestBody != null) Arrays.fill(requestBody, (byte) 0);
            if (responseBody != null) Arrays.fill(responseBody, (byte) 0);
        }
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 4096 - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("SMS acknowledgement exceeds bound")); return;
                }
                while (buffer.hasRemaining()) bytes.write(buffer.get());
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(new IllegalStateException("SMS acknowledgement unavailable")); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
    @Override public void close() { closed = true; client.shutdownNow(); }
}
