package com.smartlib.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

/** Single source of business time. Tests swap the clock to simulate overdue loans, expiries, etc. */
@Component
public class AppClock {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");

    private volatile Clock clock = Clock.systemUTC();

    public Instant now() {
        return clock.instant();
    }

    public LocalDate today(ZoneId zone) {
        return LocalDate.ofInstant(now(), zone == null ? DEFAULT_ZONE : zone);
    }

    public LocalDate today(String zone) {
        return today(zoneOf(zone));
    }

    public static ZoneId zoneOf(String zone) {
        try {
            return zone == null || zone.isBlank() ? DEFAULT_ZONE : ZoneId.of(zone);
        } catch (RuntimeException e) {
            return DEFAULT_ZONE;
        }
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public void reset() {
        this.clock = Clock.systemUTC();
    }
}
