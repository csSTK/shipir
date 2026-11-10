package dev.bethge.shipir;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Test clock whose instant only moves when the test says so: deterministic time for TTLs, rate windows, schedules and
 * cooldowns. The start instant is always passed in by the test, never read from the wall clock.
 */
public final class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    /** Moves the clock forward by {@code d}. */
    public void advance(Duration d) {
        now = now.plus(d);
    }

    /** Jumps the clock to {@code at}. */
    public void set(Instant at) {
        now = at;
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
}
