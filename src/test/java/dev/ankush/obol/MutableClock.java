package dev.ankush.obol;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock the tests can push forward.
 *
 * <p>Pending transfers expire after a configurable window, which is the one
 * rule in the ledger that cannot be checked by making a request -- only by
 * letting time pass. Sleeping for a real thirty minutes is not an option, and
 * sleeping for a contrived two seconds produces a slow test that fails on a
 * loaded CI runner. Moving the clock instead makes the expiry test both
 * instant and deterministic.
 */
public final class MutableClock extends Clock {

    private volatile Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public void advance(Duration amount) {
        now = now.plus(amount);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now, newZone);
    }
}
