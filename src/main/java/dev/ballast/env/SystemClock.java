package dev.ballast.env;

/** Production clock adapter. Queue logic must receive it through the Clock interface. */
public final class SystemClock implements Clock {
    /** Creates a host clock adapter. */
    public SystemClock() {}

    @Override
    public long wallTimeMillis() { return System.currentTimeMillis(); }

    @Override
    public long elapsedNanos() { return System.nanoTime(); }
}
