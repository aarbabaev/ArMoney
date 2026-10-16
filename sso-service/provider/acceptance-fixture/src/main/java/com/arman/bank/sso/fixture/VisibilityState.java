package com.arman.bank.sso.fixture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Process-local bounded rendezvous; never stores a Keycloak session or model. */
final class VisibilityState {
    enum Boundary { NOT_ENTERED, ENTERED, RESPONSE_REJECTED, TUPLE_REJECTED, HELD, WAIT_EXPIRED, INTERRUPTED, INTERNAL_ERROR, RELEASED }
    record Tuple(String realmId, String subject, String sessionId, String clientId, String clientUuid) { }
    static final class Entry {
        final String token = UUID.randomUUID().toString();
        final String nonce = UUID.randomUUID().toString();
        final String realm, subject, client, mode;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        final long expires = java.time.Instant.now().getEpochSecond()+30;
        final CountDownLatch release = new CountDownLatch(1);
        volatile String state = "ARMED";
        volatile Tuple tuple;
        volatile boolean finished;
        volatile Boundary boundary=Boundary.NOT_ENTERED;
        Entry(String realm, String subject, String client, String mode) {
            this.realm=realm; this.subject=subject; this.client=client; this.mode=mode;
        }
    }
    private static Entry entry;
    static synchronized void boundary(Boundary boundary) {
        expire();
        if (entry!=null && !entry.finished) entry.boundary=boundary;
    }
    static void boundary(Entry held,Boundary boundary) { held.boundary=boundary; }
    static synchronized Entry arm(String realm,String subject,String client,String mode) {
        expire();
        if (entry != null && !entry.finished) throw new IllegalStateException();
        return entry = new Entry(realm,subject,client,mode);
    }
    static synchronized Entry get(String token) {
        expire();
        if (entry == null || !equal(entry.token,token)) throw new IllegalArgumentException();
        return entry;
    }
    static synchronized Entry hold(Tuple tuple) {
        expire();
        if (entry == null || !entry.state.equals("ARMED") || !entry.realm.equals(tuple.realmId())
                || !entry.subject.equals(tuple.subject()) || !entry.client.equals(tuple.clientId())) throw new IllegalStateException();
        entry.tuple=tuple; entry.state="HELD"; entry.boundary=Boundary.HELD; return entry;
    }
    static synchronized void release(Entry e,boolean cancel) {
        if (cancel) e.state="CANCELLED";
        else if (!e.state.equals("CANCELLED")) e.state="RELEASED";
        e.release.countDown();
    }
    static synchronized void finish(Entry e) { e.release.countDown(); e.finished=true; }
    private static void expire() {
        if (entry != null && System.nanoTime() >= entry.deadline) { release(entry,true); entry=null; }
    }
    static String key() {
        String key=System.getenv("ARMONEY_VISIBILITY_CONTROL_KEY");
        if (key==null || key.getBytes(StandardCharsets.UTF_8).length<32) throw new IllegalStateException();
        return key;
    }
    static boolean equal(String a,String b) {
        return a!=null && b!=null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));
    }
    static String seal(Tuple tuple,long expires,String nonce) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            // Length prefixes prohibit separator ambiguity. No tuple data is encoded in the token.
            StringBuilder input=new StringBuilder();
            for(String value:new String[]{tuple.realmId(),tuple.subject(),tuple.sessionId(),tuple.clientId(),tuple.clientUuid(),Long.toString(expires),nonce})
                input.append(value.length()).append(':').append(value);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException(); }
    }
    private VisibilityState() { }
}
