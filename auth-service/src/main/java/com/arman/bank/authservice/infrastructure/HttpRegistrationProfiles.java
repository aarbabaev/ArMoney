package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.AuthFailure;
import com.arman.bank.authservice.application.RegistrationProfiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import static com.arman.bank.authservice.application.AuthFailure.Kind.*;

/** Fixed private destination; retries always carry the already-persisted auth identity. */
public final class HttpRegistrationProfiles implements RegistrationProfiles, AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client;
    private final URI endpoint;
    private final String serviceKey;
    private final Semaphore slots = new Semaphore(16);

    public HttpRegistrationProfiles(URI base, String serviceKey) {
        if (base == null || !("http".equals(base.getScheme()) || "https".equals(base.getScheme())) ||
                base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null ||
                !(base.getPath().isEmpty() || "/".equals(base.getPath())))
            throw new IllegalArgumentException("USER_BASE_URL must be a fixed HTTP service origin");
        if (serviceKey == null || serviceKey.length() < 32 || serviceKey.length() > 256)
            throw new IllegalArgumentException("INTERNAL_AUTH_KEY length is invalid");
        this.endpoint = base.resolve("/internal/registrations/me");
        this.serviceKey = serviceKey;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public void provision(UUID identityId, String phoneNumber) {
        if (!slots.tryAcquire()) throw new AuthFailure(UNAVAILABLE);
        CompletableFuture<HttpResponse<Void>> pending = null;
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                .header("X-Service-Key", serviceKey).header("X-Identity-Id", identityId.toString())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("phone_number", phoneNumber)))).build();
            // No response payload is trusted or retained. The total deadline includes body consumption.
            pending = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            var response = pending.get(3, TimeUnit.SECONDS);
            if (response.statusCode() == 409) throw new AuthFailure(CONFLICT);
            if (response.statusCode() != 204 ||
                    response.headers().firstValueAsLong("Content-Length").orElse(0) != 0 ||
                    response.headers().firstValue("Transfer-Encoding").isPresent())
                throw new AuthFailure(UNAVAILABLE);
        } catch (AuthFailure e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AuthFailure(UNAVAILABLE); }
        catch (Exception e) { throw new AuthFailure(UNAVAILABLE); }
        finally { if (pending != null && !pending.isDone()) pending.cancel(true); slots.release(); }
    }

    @Override public void close() { client.close(); }
}
