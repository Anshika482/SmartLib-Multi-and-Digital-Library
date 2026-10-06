package com.library.lms.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How often one client may register.
 *
 * <p>The same fixed-window shape as {@link PublicChatRateLimiter}, with its own
 * bean and its own numbers. Deliberately a second component rather than a
 * shared one: the chat limiter is working and covered by its own tests, and the
 * two protect different things at different rates - chat guards a cost per
 * call, this guards account creation and the enumeration of usernames.</p>
 *
 * <p>Tighter than chat by default: registering a handful of times from one
 * address in an hour is already unusual.</p>
 *
 * <p>In memory, one instance, no extra dependency. It curbs scripted abuse and
 * accidental floods; a determined attacker with many addresses wants a gateway,
 * and saying which problem this solves is more useful than implying the other.</p>
 */
@Component
public class RegistrationRateLimiter {

    private static final Duration SWEEP_AFTER = Duration.ofHours(2);

    private static final int SWEEP_EVERY_CALLS = 200;

    private final int limit;

    private final Duration window;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private final AtomicInteger callsSinceSweep = new AtomicInteger();

    public RegistrationRateLimiter(
            @Value("${registration.rate-limit.requests:5}") int limit,
            @Value("${registration.rate-limit.window-seconds:3600}") long windowSeconds) {
        this.limit = Math.max(1, limit);
        this.window = Duration.ofSeconds(Math.max(1, windowSeconds));
    }

    /**
     * Records one attempt and says whether it may proceed.
     *
     * @param client whatever identifies the caller; a blank key is one shared
     *               bucket rather than a way past
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

        return Math.max(0, window.minus(Duration.between(current.openedAt, Instant.now())).toSeconds());
    }

    public int limit() {
        return limit;
    }

    public long windowSeconds() {
        return window.toSeconds();
    }

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
