package com.elecsarthi.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A clock tests can set and advance. Until a time is set it follows the system clock. */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant fixed;

    public MutableClock(ZoneId zone) {
        this.zone = zone;
    }

    public void set(Instant instant) {
        this.fixed = instant;
    }

    public void advance(Duration duration) {
        this.fixed = instant().plus(duration);
    }

    public void useSystemTime() {
        this.fixed = null;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("The test clock keeps the application zone");
    }

    @Override
    public Instant instant() {
        Instant current = fixed;
        return current != null ? current : Instant.now();
    }
}
