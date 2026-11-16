package ge.opensoft.openservice;

import ge.opensoft.openservice.Values.Failure;

import org.bouncycastle.crypto.generators.SCrypt;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.Normalizer;
import java.util.*;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Passwords {
    private Passwords() {}

    private static final SecureRandom RANDOM = new SecureRandom();

    /** New credentials only: existing hashes retain their original authentication semantics. */
    static String requireNewPassword(Object candidate) {
        if (!(candidate instanceof String raw))
            throw new Failure(400, "Password must contain 15–1024 characters");
        String password = normalize(raw);
        if (password.codePointCount(0, password.length()) < 15
                || password.codePointCount(0, password.length()) > 1024)
            throw new Failure(400, "Password must contain 15–1024 characters");
        return password;
    }

    static String normalize(String password) {
        return Normalizer.normalize(password, Normalizer.Form.NFC);
    }

    static boolean withinLimit(String password) {
        String normalized = normalize(password);
        return normalized.codePointCount(0, normalized.length()) <= 1024;
    }

    static boolean requireLength(String encoded, String password) {
        String counted = encoded.startsWith("nfc$") ? normalize(password) : password;
        return counted.codePointCount(0, counted.length()) <= 1024;
    }

    public static String token() {
        byte[] value = new byte[24];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static String tokenHash(String token) throws GeneralSecurityException {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    public static String hash(String password) throws GeneralSecurityException {
        String salt = token();
        return "nfc$pbkdf2:sha256:600000$"
                + salt
                + "$"
                + HexFormat.of().formatHex(derive(normalize(password), salt, 600000));
    }

    private static byte[] derive(String password, String salt, int iterations)
            throws GeneralSecurityException {
        var spec =
                new PBEKeySpec(
                        password.toCharArray(),
                        salt.getBytes(StandardCharsets.UTF_8),
                        iterations,
                        256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    public static boolean matches(String encoded, String password) throws GeneralSecurityException {
        try {
            if (encoded.startsWith("nfc$")) {
                encoded = encoded.substring(4);
                password = normalize(password);
            }
            String[] parts = encoded.split("\\$", -1), method = parts[0].split(":");
            if (parts.length != 3) return false;
            byte[] actual;
            if (method.length == 3 && method[0].equals("pbkdf2") && method[1].equals("sha256")) {
                int iterations = Integer.parseInt(method[2]);
                if (iterations < 1 || iterations > 10000000) return false;
                actual = derive(password, parts[1], iterations);
            } else if (method.length == 4 && method[0].equals("scrypt")) {
                int n = Integer.parseInt(method[1]),
                        r = Integer.parseInt(method[2]),
                        p = Integer.parseInt(method[3]);
                if (n < 2 || n > 65536 || r < 1 || r > 16 || p < 1 || p > 4) return false;
                actual =
                        SCrypt.generate(
                                password.getBytes(StandardCharsets.UTF_8),
                                parts[1].getBytes(StandardCharsets.UTF_8),
                                n,
                                r,
                                p,
                                64);
            } else return false;
            return MessageDigest.isEqual(actual, HexFormat.of().parseHex(parts[2]));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
