package com.arman.bank.sso;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.keycloak.util.JsonSerialization;

/** Unregistered integration client. Separate directional credentials; no response details escape. */
final class ProofClient {
    private final URI base;
    private final String writeKey, resolveKey;
    private final HttpClient http;
    ProofClient(URI base, String writeKey, String resolveKey) {
        this(base, writeKey, resolveKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    ProofClient(URI base, String writeKey, String resolveKey, HttpClient http) {
        new Bridge(base, writeKey); new Bridge(base, resolveKey);
        if (writeKey.length() > 256 || resolveKey.length() > 256 || writeKey.equals(resolveKey)) LoginProof.invalid();
        this.base = base; this.writeKey = writeKey; this.resolveKey = resolveKey; this.http = http;
    }
    LoginProof.Snapshot write(LoginProof.Write request) {
        return LoginProof.snapshot(post("", request.fields(), writeKey, "X-ArMoney-Login-Proof-Write-Key"), true, request);
    }
    LoginProof.Snapshot resolve(CodeKeys keys, LoginProof.Binding binding, String codeId, Instant codeExpiry, Instant now) {
        for (CodeKeys.Key key : keys.keys()) {
            var fields = binding.fields(); fields.put("code_digest", keys.digest(key, binding, codeId)); fields.put("code_key_id", key.id());
            try {
                var snapshot = LoginProof.snapshot(post("/resolve", fields, resolveKey, "X-ArMoney-Login-Proof-Resolve-Key"), false, null);
                if (!snapshot.expires().equals(codeExpiry) || !now.isBefore(snapshot.expires()) || snapshot.issued().isAfter(now.plusSeconds(30))) throw new Failure();
                return snapshot;
            } catch (Missing absent) { /* Only explicit absence permits bounded previous-key lookup. */ }
        }
        throw new Failure();
    }
    private com.fasterxml.jackson.databind.JsonNode post(String suffix, Map<String,Object> fields, String key, String header) {
        try {
            byte[] body = JsonSerialization.writeValueAsBytes(fields); if (body.length > 12288) throw new Failure();
            var request = HttpRequest.newBuilder(base.resolve("/internal/sso-login-proofs" + suffix)).timeout(Duration.ofSeconds(4))
                    .header("Content-Type", "application/json").header(header, key).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response = DeadlineHttp.send(http,request);
            if (suffix.equals("/resolve") && response.statusCode() == 404) throw new Missing();
            if (response.statusCode() != 200 || !"no-store".equals(response.headers().firstValue("Cache-Control").orElse(""))) throw new Failure();
            return Bridge.strictJson(response.body());
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Failure(); }
        catch (Missing | Failure known) { throw known; }
        catch (Exception failure) { throw new Failure(); }
    }
    static final class Failure extends RuntimeException { Failure() { super("Login proof unavailable"); } }
    private static final class Missing extends RuntimeException { }
}
