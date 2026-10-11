package com.smartlib.resource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.smartlib.security.TokenHasher;

/** Short-lived signed file URLs: knowing a URL is useless once it expires, and ids cannot be guessed into access. */
@Component
public class FileTokens {

    private final TokenHasher hasher;

    public FileTokens(TokenHasher hasher) {
        this.hasher = hasher;
    }

    public String sign(long resourceId, Instant expiresAt) {
        String payload = "res:" + resourceId + ":" + expiresAt.getEpochSecond();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + hasher.pepperedHash(payload);
    }

    public Optional<Long> verify(String token, Instant now) {
        try {
            int dot = token.indexOf('.');
            if (dot <= 0) {
                return Optional.empty();
            }
            String payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            if (!TokenHasher.constantTimeEquals(hasher.pepperedHash(payload), token.substring(dot + 1))) {
                return Optional.empty();
            }
            String[] parts = payload.split(":");
            if (parts.length != 3 || !"res".equals(parts[0]) || Instant.ofEpochSecond(Long.parseLong(parts[2])).isBefore(now)) {
                return Optional.empty();
            }
            return Optional.of(Long.parseLong(parts[1]));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
