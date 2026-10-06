package com.library.lms.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How often an unauthenticated visitor may ask the assistant.
 *
 * <p>A public endpoint that costs money per call needs a ceiling. This is a
 * fixed window per client: a counter and the instant its window opened, kept
 * in memory and reset when the window rolls over.</p>
 *
 * <p><b>In memory, and deliberately so.</b> One application instance, one map,
 * no extra dependency and no round trip. The limits this enforces are about
 * curbing casual abuse and runaway cost, not about defeating a determined
 * attacker with a pool of addresses - that wants a gateway or a shared store,
 * and saying which problem this solves is more useful than implying it solves
 * the other.</p>
 *
 * <p>Nothing here identifies a person. The key is whatever the caller's remote
 * address is, held only until the entry is swept, and never logged.</p>
 */
@Component
public class PublicChatRateLimiter {

    /** Entries idle for longer than this are dropped, so the map cannot grow without bound. */
    private static final Duration SWEEP_AFTER = Duration.ofMinutes(30);

    private static final int SWEEP_EVERY_CALLS = 500;

    private final int limit;

    private final Duration window;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private final AtomicInteger callsSinceSweep = new AtomicInteger();

    public PublicChatRateLimiter(
            @Value("${chat.public.rate-limit.requests:8}") int limit,
            @Value("${chat.public.rate-limit.window-seconds:300}") long windowSeconds) {
        this.limit = Math.max(1, limit);
        this.window = Duration.ofSeconds(Math.max(1, windowSeconds));
    }

    /**
     * Records one attempt and says whether it may proceed.
     *
     * @param client whatever identifies the caller; a blank key is treated as
     *               one shared bucket rather than waved through
     */
    public boolean tryAcquire(String client) {
        sweepOccasionally();

        String key = client == null || client.isBlank() ? "unknown" : client;
        Instant now = Instant.now();

        Window updated = windows.compute(key, (ignored, existing) -> {
            if (existing == null || Duration.between(existing.openedAt, now).compareTo(window) >= 0) {
                return new Window(now, 1);
            }
            return new Window(existing.openedAt, existing.count + 1);
        });

        return updated.count <= limit;
    }

    /** How long until the caller's window rolls over, for the Retry-After header. */
    public long secondsUntilReset(String client) {
        Window current = windows.get(client == null || client.isBlank() ? "unknown" : client);
        if (current == null) {
            return 0;
        }

        long remaining = window.minus(Duration.between(current.openedAt, Instant.now())).toSeconds();
        return Math.max(0, remaining);
    }

    /** How many requests a window allows, so a refusal can say. */
    public int limit() {
        return limit;
    }

    public long windowSeconds() {
        return window.toSeconds();
    }

    /**
     * Drops entries nobody has touched for a while.
     *
     * <p>Every few hundred calls rather than on a schedule: it keeps the class
     * free of a timer and a bean lifecycle, and the map only grows while
     * requests are arriving anyway.</p>
     */
    private void sweepOccasionally() {
        if (callsSinceSweep.incrementAndGet() < SWEEP_EVERY_CALLS) {
            return;
        }

        callsSinceSweep.set(0);
        Instant cutoff = Instant.now().minus(SWEEP_AFTER);
        windows.entrySet().removeIf(entry -> entry.getValue().openedAt.isBefore(cutoff));
    }

    private record Window(Instant openedAt, int count) {
    }
}
