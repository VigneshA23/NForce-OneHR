package com.nforce.onehr.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Real system time plus an adjustable offset. The offset defaults to zero — production
 * behavior is always real wall-clock time. Only {@code app.testing.enabled=true} exposes a
 * way to move the offset, letting E2E runs fast-forward the
 * 4-hour login lockout window without waiting or touching the database directly.
 */
public class MutableClock extends Clock {

    private final Clock base = Clock.systemUTC();
    private volatile Duration offset = Duration.ZERO;

    @Override
    public ZoneId getZone() {
        return base.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset);
    }

    public void advanceBy(Duration duration) {
        offset = offset.plus(duration);
    }

    public void reset() {
        offset = Duration.ZERO;
    }
}
