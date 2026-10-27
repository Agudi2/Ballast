package dev.ballast.simulation;

import dev.ballast.env.Clock;

/** Explicit virtual time; no host clock or sleeping. Controlled by a single simulation thread. */
public final class VirtualClock implements Clock {
    private long wallMillis;
    private long elapsedNanos;
    private long wallFractionNanos;

    public VirtualClock(long wallMillis) { this.wallMillis = wallMillis; }

    @Override
    public long wallTimeMillis() { return wallMillis; }
    @Override
    public long elapsedNanos() { return elapsedNanos; }

    public void advanceNanos(long nanos) {
        if (nanos < 0) throw new IllegalArgumentException("time cannot advance by a negative duration");
        long nextElapsed = Math.addExact(elapsedNanos, nanos);
        long fraction = wallFractionNanos + nanos % 1_000_000;
        long nextWall = Math.addExact(wallMillis, nanos / 1_000_000 + fraction / 1_000_000);
        elapsedNanos = nextElapsed;
        wallMillis = nextWall;
        wallFractionNanos = fraction % 1_000_000;
    }

    public void advanceElapsedNanos(long nanos) {
        if (nanos < 0) throw new IllegalArgumentException("elapsed time cannot move backward");
        elapsedNanos = Math.addExact(elapsedNanos, nanos);
    }

    public void jumpWallMillis(long delta) { wallMillis = Math.addExact(wallMillis, delta); }
}
