package com.smartlib.platform;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartlib.common.error.ApiException;

/** Small set of platform-wide switches managed by SUPER_ADMIN. */
@Service
public class PlatformSettings {

    public static final String AI_ENABLED = "ai.enabled";
    public static final String LIBRARIAN_REGISTRATION_OPEN = "registration.librarian.open";
    public static final String ADMIN_REGISTRATION_OPEN = "registration.admin.open";
    public static final String ANNOUNCEMENT = "platform.announcement";
    public static final Set<String> KEYS = Set.of(AI_ENABLED, LIBRARIAN_REGISTRATION_OPEN, ADMIN_REGISTRATION_OPEN, ANNOUNCEMENT);
    private static final Set<String> BOOLEAN_KEYS = Set.of(AI_ENABLED, LIBRARIAN_REGISTRATION_OPEN, ADMIN_REGISTRATION_OPEN);

    private final PlatformSettingRepository repository;

    public PlatformSettings(PlatformSettingRepository repository) {
        this.repository = repository;
    }

    public String get(String key, String fallback) {
        return repository.findById(key).map(PlatformSetting::getSettingValue).orElse(fallback);
    }

    public boolean isEnabled(String key) {
        return Boolean.parseBoolean(get(key, "true"));
    }

    public Map<String, String> all() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : KEYS.stream().sorted().toList()) {
            map.put(key, get(key, BOOLEAN_KEYS.contains(key) ? "true" : ""));
        }
        return map;
    }

    @Transactional
    public void set(String key, String value, Long actorId) {
        if (!KEYS.contains(key)) {
            throw ApiException.validation(key, "Unknown setting");
        }
        String v = value == null ? "" : value.trim();
        if (BOOLEAN_KEYS.contains(key) && !v.equals("true") && !v.equals("false")) {
            throw ApiException.validation(key, "Must be true or false");
        }
        if (v.length() > 500) {
            throw ApiException.validation(key, "Value is too long");
        }
        PlatformSetting s = repository.findById(key).orElseGet(() -> {
            PlatformSetting n = new PlatformSetting();
            n.setSettingKey(key);
            return n;
        });
        s.setSettingValue(v);
        s.setUpdatedBy(actorId);
        s.setUpdatedAt(Instant.now());
        repository.save(s);
    }
}
