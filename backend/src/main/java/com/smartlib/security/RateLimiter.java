package com.smartlib.security;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;

/** In-memory token-bucket rate limiter (single-instance deployment). */
@Component
public class RateLimiter {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(25))
            .maximumSize(200_000)
            .build();
    private volatile boolean enabled = true;

    /** Consumes one token from bucket {@code key}; throws 429 with Retry-After when empty. */
    public void check(String key, int capacity, Duration period) {
        if (!enabled) {
            return;
        }
        Bucket bucket = buckets.get(key + '|' + capacity + '|' + period.toMillis(), k -> new Bucket(capacity));
        long waitMillis = bucket.tryConsume(capacity, period);
        if (waitMillis > 0) {
            long seconds = Math.max(1, (waitMillis + 999) / 1000);
            throw ApiException.tooManyRequests(ErrorCodes.RATE_LIMITED,
                    "Too many requests. Please try again in " + seconds + " seconds.", seconds);
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void reset() {
        buckets.invalidateAll();
    }

    private static final class Bucket {
        private double tokens;
        private long lastNanos = System.nanoTime();

        Bucket(int capacity) {
            this.tokens = capacity;
        }

        synchronized long tryConsume(int capacity, Duration period) {
            long now = System.nanoTime();
            double perNano = capacity / (double) period.toNanos();
            tokens = Math.min(capacity, tokens + (now - lastNanos) * perNano);
            lastNanos = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            double missing = 1 - tokens;
            return (long) Math.ceil(missing / perNano / 1_000_000d);
        }
    }
}
