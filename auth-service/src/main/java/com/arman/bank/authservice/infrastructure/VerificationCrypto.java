package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.domain.Verification.Purpose;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Independently provisioned OTP HMAC and delivery encryption keys; never logged. */
public final class VerificationCrypto {
    private final String current;
    private final Map<String, byte[]> hmac;
    private final Map<String, byte[]> encryption;
    private final SecureRandom random = new SecureRandom();
    public VerificationCrypto(String current, Map<String, byte[]> hmac, Map<String, byte[]> encryption) {
        this.current = Objects.requireNonNull(current);
        this.hmac = keys(hmac); this.encryption = keys(encryption);
        if (!this.hmac.containsKey(current) || !this.encryption.containsKey(current)) invalid();
        for (byte[] a : this.hmac.values()) for (byte[] b : this.encryption.values())
            if (MessageDigest.isEqual(a, b)) invalid();
    }
    private static Map<String, byte[]> keys(Map<String, byte[]> input) {
        if (input == null || input.isEmpty() || input.size() > 2) { invalid(); }
        var result = new HashMap<String, byte[]>();
        input.forEach((id, key) -> {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}") || key == null || key.length != 32) invalid();
            result.put(id, key.clone());
        });
        return Collections.unmodifiableMap(result);
    }
    private static void invalid() { throw new IllegalArgumentException("Invalid verification key configuration"); }
    public String keyId() { return current; }
    public String newCode() { return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000)); }
    public byte[] fingerprint(String keyId, String... values) {
        try {
            byte[] key = hmac.get(keyId);
            if (key == null) throw new IllegalStateException("Verification key unavailable");
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(tuple(values));
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Verification cryptography unavailable"); }
    }
    public byte[] codeDigest(String keyId, UUID enrollment, String issuer, String subject,
            UUID challenge, Purpose purpose, int version, String contact, String code) {
        return fingerprint(keyId, "otp-v1", enrollment.toString(), issuer, subject, challenge.toString(), purpose.name(),
            Integer.toString(version), hex(fingerprint(keyId, "contact-v1", contact)), code);
    }
    public boolean matches(byte[] expected, byte[] supplied) {
        return expected != null && supplied != null && MessageDigest.isEqual(expected, supplied);
    }
    public record Encrypted(String keyId, byte[] nonce, byte[] ciphertext) {
        public Encrypted { nonce = nonce.clone(); ciphertext = ciphertext.clone(); }
        @Override public byte[] nonce() { return nonce.clone(); }
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
        @Override public String toString() { return "Encrypted[redacted]"; }
    }
    public record DeliveryPayload(String destination, String code) {
        @Override public String toString() { return "DeliveryPayload[redacted]"; }
    }
    public Encrypted encrypt(UUID enrollment, UUID challenge, Purpose purpose, int version, String contact, String code) {
        byte[] nonce = new byte[12]; random.nextBytes(nonce);
        return new Encrypted(current, nonce, crypt(Cipher.ENCRYPT_MODE, current, nonce,
            metadata(enrollment, challenge, purpose, version), tuple(contact, code)));
    }
    public DeliveryPayload decrypt(Encrypted encrypted, UUID enrollment, UUID challenge, Purpose purpose, int version) {
        byte[] plain = crypt(Cipher.DECRYPT_MODE, encrypted.keyId(), encrypted.nonce(),
            metadata(enrollment, challenge, purpose, version), encrypted.ciphertext());
        try (var input = new DataInputStream(new ByteArrayInputStream(plain))) {
            String destination = read(input), code = read(input);
            if (input.available() != 0 || !code.matches("[0-9]{6}")) throw new IOException();
            return new DeliveryPayload(destination, code);
        } catch (IOException e) { throw new IllegalStateException("Invalid verification delivery payload"); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    private byte[] crypt(int mode, String id, byte[] nonce, byte[] aad, byte[] data) {
        try {
            byte[] key = encryption.get(id);
            if (key == null || nonce.length != 12) throw new GeneralSecurityException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad); return cipher.doFinal(data);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Verification delivery cryptography failed"); }
    }
    private static String read(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 512 || length > input.available()) throw new IOException();
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }
    private static byte[] metadata(UUID enrollment, UUID challenge, Purpose purpose, int version) {
        return tuple("delivery-v1", enrollment.toString(), challenge.toString(), purpose.name(), Integer.toString(version));
    }
    private static byte[] tuple(String... values) {
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            for (String value : values) {
                byte[] field = Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8);
                output.writeInt(field.length); output.write(field);
            }
            return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("Verification tuple unavailable"); }
    }
    public static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
}
