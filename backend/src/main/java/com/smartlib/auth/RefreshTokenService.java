package com.smartlib.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartlib.audit.AuditActions;
import com.smartlib.audit.AuditService;
import com.smartlib.common.config.AppProperties;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.util.Texts;
import com.smartlib.security.TokenHasher;

/**
 * Opaque refresh tokens, stored hashed, rotated on every use. Re-use of a rotated token (outside a short grace window
 * that tolerates two tabs refreshing at once) is treated as theft and revokes the whole token family.
 */
@Service
public class RefreshTokenService {

    private static final Duration ROTATION_GRACE = Duration.ofSeconds(10);

    private final RefreshTokenRepository repository;
    private final Duration ttl;
    private final AuditService audit;

    public RefreshTokenService(RefreshTokenRepository repository, AppProperties properties, AuditService audit) {
        this.repository = repository;
        this.ttl = properties.jwt().refreshTtl();
        this.audit = audit;
    }

    public record Rotation(long userId, String rawToken) {
    }

    @Transactional
    public String issue(long userId, String familyId, String ip, String userAgent) {
        String raw = TokenHasher.newToken();
        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setTokenHash(TokenHasher.sha256Hex(raw));
        token.setFamilyId(familyId == null ? UUID.randomUUID().toString() : familyId);
        token.setCreatedAt(Instant.now());
        token.setExpiresAt(Instant.now().plus(ttl));
        token.setIp(ip);
        token.setUserAgent(Texts.truncate(userAgent, 255));
        repository.save(token);
        return raw;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String raw, String ip, String userAgent) {
        if (raw == null || raw.isBlank()) {
            throw invalid();
        }
        RefreshToken current = repository.findByTokenHash(TokenHasher.sha256Hex(raw)).orElseThrow(RefreshTokenService::invalid);
        Instant now = Instant.now();
        if (current.getRevokedAt() != null) {
            boolean benignRace = current.getReplacedById() != null && current.getRevokedAt().isAfter(now.minus(ROTATION_GRACE));
            if (!benignRace) {
                repository.revokeFamily(current.getFamilyId(), now);
                audit.recordIndependent(new AuditService.Actor(current.getUserId(), null, null), AuditActions.REFRESH_REUSE_DETECTED,
                        "USER", current.getUserId(), null, "FAILURE", null);
                throw invalid();
            }
        } else if (now.isAfter(current.getExpiresAt())) {
            throw invalid();
        }
        String next = issue(current.getUserId(), current.getFamilyId(), ip, userAgent);
        if (current.getRevokedAt() == null) {
            current.setRevokedAt(now);
            current.setReplacedById(repository.findByTokenHash(TokenHasher.sha256Hex(next)).map(RefreshToken::getId).orElse(null));
            repository.save(current);
        }
        return new Rotation(current.getUserId(), next);
    }

    @Transactional
    public void revokeFamilyOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        repository.findByTokenHash(TokenHasher.sha256Hex(raw)).ifPresent(t -> repository.revokeFamily(t.getFamilyId(), Instant.now()));
    }

    @Transactional
    public void revokeAll(long userId) {
        repository.revokeAllForUser(userId, Instant.now());
    }

    public Duration ttl() {
        return ttl;
    }

    private static ApiException invalid() {
        return ApiException.unauthorized(ErrorCodes.INVALID_REFRESH_TOKEN, "Your session has expired. Please log in again.");
    }
}
