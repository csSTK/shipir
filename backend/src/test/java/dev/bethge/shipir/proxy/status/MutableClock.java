package dev.bethge.shipir.proxy.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock whose instant the test moves forward by hand. */
final class MutableClock extends Clock {

    volatile Instant now;

    MutableClock() {
        this(Instant.now());
    }

    MutableClock(Instant now) {
        this.now = now;
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
}
