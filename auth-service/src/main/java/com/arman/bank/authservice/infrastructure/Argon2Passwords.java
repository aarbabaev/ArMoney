package com.arman.bank.authservice.infrastructure;

import com.arman.bank.authservice.application.PasswordHasher;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class Argon2Passwords implements PasswordHasher {
    private static final String PREFIX = "$argon2id$v=19$m=19456,t=2,p=1$";
    private final SecureRandom random = new SecureRandom();

    @Override public String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return PREFIX + encode(salt) + "$" + encode(derive(password, salt));
    }

    @Override public boolean verify(String password, String encoded) {
        if (encoded == null || !encoded.startsWith(PREFIX)) return false;
        try {
            var parts = encoded.substring(PREFIX.length()).split("\\$", -1);
            if (parts.length != 2) return false;
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            if (salt.length != 16 || expected.length != 32) return false;
            return MessageDigest.isEqual(expected, derive(password, salt));
        } catch (IllegalArgumentException e) { return false; }
    }

    private static byte[] derive(String password, byte[] salt) {
        var generator = new Argon2BytesGenerator();
        generator.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(19456)
                .withIterations(2).withParallelism(1).withSalt(salt).build());
        byte[] result = new byte[32];
        char[] chars = password.toCharArray();
        try { generator.generateBytes(chars, result); }
        finally { Arrays.fill(chars, '\0'); }
        return result;
    }

    private static String encode(byte[] bytes) { return Base64.getEncoder().withoutPadding().encodeToString(bytes); }
}
