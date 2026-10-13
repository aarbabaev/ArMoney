package com.arman.bank.paymentservice.infrastructure;

import com.arman.bank.paymentservice.application.PagePosition;
import com.arman.bank.paymentservice.application.PaymentFailure;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated, owner/resource-bound cursor. Clients must treat its encoding as opaque. */
public final class PageCursor {
    private static final int PAYLOAD_SIZE = 46;
    private static final byte[] PURPOSE = "ArMoney history cursor v1\0".getBytes(StandardCharsets.UTF_8);
    private final SecretKeySpec key;
    public PageCursor(String key) { this.key = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"); }

    public String encode(UUID owner, boolean notifications, PagePosition position) {
        var payload = ByteBuffer.allocate(PAYLOAD_SIZE);
        payload.put((byte) 1).put((byte) (notifications ? 1 : 0));
        putUuid(payload, owner);
        payload.putLong(position.createdAt().getEpochSecond()).putInt(position.createdAt().getNano());
        putUuid(payload, position.id());
        var result = ByteBuffer.allocate(PAYLOAD_SIZE + 32).put(payload.array()).put(sign(payload.array()));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(result.array());
    }
    public PagePosition decode(String cursor, UUID owner, boolean notifications) {
        try {
            if (cursor == null || cursor.length() > 512 || !cursor.matches("[A-Za-z0-9_-]{104}")) throw invalid();
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            byte[] payload = Arrays.copyOf(bytes, PAYLOAD_SIZE);
            if (!MessageDigest.isEqual(sign(payload), Arrays.copyOfRange(bytes, PAYLOAD_SIZE, bytes.length))) throw invalid();
            var data = ByteBuffer.wrap(payload);
            if (data.get() != 1 || data.get() != (notifications ? 1 : 0) || !getUuid(data).equals(owner)) throw invalid();
            long seconds = data.getLong();
            int nanos = data.getInt();
            if (nanos < 0 || nanos >= 1_000_000_000 || nanos % 1000 != 0 || seconds < -62135596800L || seconds > 253402300799L) throw invalid();
            return new PagePosition(Instant.ofEpochSecond(seconds, nanos), getUuid(data));
        } catch (IllegalArgumentException e) { throw invalid(); }
    }
    private byte[] sign(byte[] payload) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(PURPOSE);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Cursor signing unavailable", e); }
    }
    private static void putUuid(ByteBuffer b, UUID id) { b.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()); }
    private static UUID getUuid(ByteBuffer b) { return new UUID(b.getLong(), b.getLong()); }
    public static PaymentFailure invalid() { return new PaymentFailure(400, "invalid_page_request"); }
}
