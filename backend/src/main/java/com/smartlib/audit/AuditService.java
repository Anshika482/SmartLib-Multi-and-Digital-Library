package com.smartlib.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.smartlib.common.time.AppClock;
import com.smartlib.common.web.ClientInfo;
import com.smartlib.security.CurrentUser;
import com.smartlib.security.SmartLibPrincipal;

import tools.jackson.databind.ObjectMapper;

/**
 * Records security-relevant and business actions. Details are redacted: any key that looks like a secret
 * (password, token, otp, signature, key, secret) is dropped before persisting.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final Pattern SENSITIVE = Pattern.compile("(?i).*(password|token|secret|otp|signature|apikey|api_key|credential|key).*");

    private final AuditWriter writer;
    private final AppClock clock;
    private final ObjectMapper mapper;

    public AuditService(AuditWriter writer, AppClock clock, ObjectMapper mapper) {
        this.writer = writer;
        this.clock = clock;
        this.mapper = mapper;
    }

    public record Actor(Long userId, String role, String email) {
        public static Actor of(SmartLibPrincipal p) {
            return p == null ? new Actor(null, null, null) : new Actor(p.userId(), p.role().name(), p.email());
        }

        public static Actor system() {
            return new Actor(null, "SYSTEM", null);
        }
    }

    /** Success event by the current caller, written in the current transaction. */
    public void record(String action, String entityType, Object entityId, Long libraryId, Map<String, Object> details) {
        writer.writeInCurrentTransaction(build(currentActor(), action, entityType, entityId, libraryId, "SUCCESS", details));
    }

    public void recordAs(Actor actor, String action, String entityType, Object entityId, Long libraryId, Map<String, Object> details) {
        writer.writeInCurrentTransaction(build(actor, action, entityType, entityId, libraryId, "SUCCESS", details));
    }

    /** Written in its own transaction (failed logins, wrong OTPs, payment verification failures...). */
    public void recordIndependent(Actor actor, String action, String entityType, Object entityId, Long libraryId,
                                  String outcome, Map<String, Object> details) {
        try {
            writer.writeIndependently(build(actor, action, entityType, entityId, libraryId, outcome, details));
        } catch (RuntimeException e) {
            log.warn("Failed to write audit event {}: {}", action, e.getMessage());
        }
    }

    public static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return map;
    }

    private Actor currentActor() {
        return CurrentUser.optional().map(Actor::of).orElse(new Actor(null, null, null));
    }

    private AuditLog build(Actor actor, String action, String entityType, Object entityId, Long libraryId, String outcome,
                           Map<String, Object> details) {
        AuditLog entry = new AuditLog();
        entry.setOccurredAt(clock.now());
        entry.setActorUserId(actor.userId());
        entry.setActorRole(actor.role());
        entry.setActorEmail(actor.email());
        entry.setLibraryId(libraryId);
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId == null ? null : String.valueOf(entityId));
        entry.setOutcome(outcome);
        HttpServletRequest request = ClientInfo.currentRequest();
        entry.setIp(ClientInfo.ip(request));
        entry.setUserAgent(ClientInfo.userAgent(request));
        entry.setRequestId(ClientInfo.currentRequestId());
        if (details != null && !details.isEmpty()) {
            Map<String, Object> safe = new LinkedHashMap<>();
            details.forEach((k, v) -> {
                if (!SENSITIVE.matcher(k).matches()) {
                    safe.put(k, v);
                }
            });
            entry.setDetails(safe.isEmpty() ? null : mapper.writeValueAsString(safe));
        }
        return entry;
    }
}
