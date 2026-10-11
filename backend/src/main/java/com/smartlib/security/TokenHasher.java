package com.smartlib.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.smartlib.common.config.AppProperties;

/** Generation and one-way hashing of one-time secrets (OTPs, verification links, reset tokens, refresh tokens). */
@Component
public class TokenHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] pepper;

    public TokenHasher(AppProperties properties) {
        String p = properties.security() == null ? null : properties.security().tokenPepper();
        if (p == null || p.isBlank()) {
            throw new IllegalStateException("APP_TOKEN_PEPPER must be configured (see .env.example)");
        }
        this.pepper = p.getBytes(StandardCharsets.UTF_8);
    }

    /** Keyed hash for low-entropy secrets (6-digit OTP): useless to an attacker without the server pepper. */
    public String pepperedHash(String value) {
        return hmacSha256Hex(pepper, value);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hmacSha256Hex(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hmacSha256Hex(String key, String data) {
        return hmacSha256Hex(key.getBytes(StandardCharsets.UTF_8), data);
    }

    /** 256-bit URL-safe random token. */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String newOtp() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
