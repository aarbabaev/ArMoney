package com.arman.bank.sso;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

/** Wire records contain no redeemable code or notification recipient. */
final class LoginProof {
    record Binding(String issuer, String subject, String client, String session) {
        Binding {
            var uri = java.net.URI.create(issuer);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || issuer.length() > 512
                    || !bounded(subject) || !bounded(session) || !client.matches("[A-Za-z0-9_-]{1,128}")) invalid();
        }
        Map<String,Object> fields() {
            var result = new LinkedHashMap<String,Object>(); result.put("issuer", issuer); result.put("subject", subject);
            result.put("client_id", client); result.put("user_session_id", session); return result;
        }
        @Override public String toString() { return "LoginProofBinding[REDACTED]"; }
    }
    record Write(UUID command, String digest, String keyId, Binding binding, String authenticationSession,
                 Instant issued, Instant expires, String kind, UUID event, Instant passwordTime) {
        Write {
            if (command == null || binding == null || !bounded(authenticationSession)) invalid();
            LoginProof.digest(digest, keyId); window(issued, expires);
            if (!("interactive".equals(kind) || "reuse".equals(kind))) invalid();
            if ("interactive".equals(kind)) {
                if (event == null || passwordTime == null || passwordTime.getNano() != 0 || passwordTime.isAfter(issued)
                        || passwordTime.isBefore(issued.minusSeconds(1800))) invalid();
            } else if (event != null || passwordTime != null) invalid();
        }
        Map<String,Object> fields() {
            var result = binding.fields(); result.put("command_id", command.toString()); result.put("code_digest", digest);
            result.put("code_key_id", keyId); result.put("authentication_session_id", authenticationSession);
            result.put("issued_at", issued.toString()); result.put("expires_at", expires.toString()); result.put("kind", kind);
            result.put("event_id", event == null ? null : event.toString());
            result.put("password_authenticated_at", passwordTime == null ? null : passwordTime.toString()); return result;
        }
        @Override public String toString() { return "LoginProofWrite[REDACTED]"; }
    }
    record Snapshot(UUID proof, UUID event, String kind, boolean eligible, Instant issued, Instant expires) {
        Snapshot {
            if (proof == null || !("interactive".equals(kind) || "reuse".equals(kind))) invalid();
            window(issued, expires);
            if ("interactive".equals(kind) ? event == null : event != null || eligible) invalid();
        }
        @Override public String toString() { return "LoginProofSnapshot[REDACTED]"; }
    }
    static Snapshot snapshot(JsonNode node, boolean write, Write expected) {
        Set<String> required = new HashSet<>(Set.of("proof_id", "event_id", "kind", "login_notice_eligible", "issued_at", "expires_at"));
        if (write) { required.add("code_key_id"); required.add("state"); }
        fields(node, required);
        if (!node.path("login_notice_eligible").isBoolean()) invalid();
        UUID event = node.path("event_id").isNull() ? null : uuid(text(node, "event_id"));
        var snapshot = new Snapshot(uuid(text(node,"proof_id")), event, text(node,"kind"), node.path("login_notice_eligible").booleanValue(),
                time(text(node,"issued_at")), time(text(node,"expires_at")));
        if (write && (!"RECORDED".equals(text(node,"state")) || !expected.keyId.equals(text(node,"code_key_id"))
                || !Objects.equals(expected.event, event) || !expected.kind.equals(snapshot.kind)
                || !expected.issued.equals(snapshot.issued) || !expected.expires.equals(snapshot.expires))) invalid();
        return snapshot;
    }
    static Write write(JsonNode node) {
        fields(node, Set.of("command_id","code_digest","code_key_id","issuer","subject","client_id","user_session_id",
                "authentication_session_id","issued_at","expires_at","kind","event_id","password_authenticated_at"));
        return new Write(uuid(text(node,"command_id")),text(node,"code_digest"),text(node,"code_key_id"),
                new Binding(text(node,"issuer"),text(node,"subject"),text(node,"client_id"),text(node,"user_session_id")),
                text(node,"authentication_session_id"),time(text(node,"issued_at")),time(text(node,"expires_at")),text(node,"kind"),
                node.path("event_id").isNull() ? null : uuid(text(node,"event_id")),
                node.path("password_authenticated_at").isNull() ? null : time(text(node,"password_authenticated_at")));
    }
    static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) invalid();
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) invalid();
    }
    static String text(JsonNode node, String name) { if (!node.path(name).isTextual()) invalid(); return node.path(name).textValue(); }
    static UUID uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IllegalArgumentException("Invalid proof UUID");
        return UUID.fromString(value);
    }
    static Instant time(String value) {
        Instant time = Instant.parse(value);
        if (time.getNano() != 0 || !time.toString().equals(value)) invalid(); return time;
    }
    static void digest(String digest, String key) {
        if (digest == null || !digest.matches("[0-9a-f]{64}") || key == null || !key.matches("[A-Za-z0-9_-]{1,32}")) invalid();
    }
    private static boolean bounded(String value) { return value != null && !value.isEmpty() && value.length() <= 255 && value.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)); }
    private static void window(Instant issued, Instant expires) {
        if (issued == null || expires == null || issued.getNano() != 0 || expires.getNano() != 0
                || !expires.isAfter(issued) || expires.isAfter(issued.plusSeconds(120))) invalid();
    }
    static void invalid() { throw new IllegalArgumentException("Invalid login proof"); }
    private LoginProof() { }
}
