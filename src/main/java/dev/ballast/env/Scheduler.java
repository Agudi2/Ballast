package dev.ballast.env;

import java.time.Duration;
import java.util.Objects;

/** Serial callbacks with FIFO registration order for equal deadlines and no early execution. */
public interface Scheduler extends AutoCloseable {
    /**
     * Schedules one callback. Zero delay is allowed; negative or unrepresentable delay is rejected.
     * Callbacks may run late. Closing rejects new submissions and cancels pending callbacks.
     *
     * @param delay elapsed duration before the callback becomes eligible
     * @param callback action, never null
     * @return a handle that can prevent a callback that has not started
     */
    Cancellation schedule(Duration delay, Runnable callback);

    /** Cancels pending work; does not interrupt or wait for a callback already executing. */
    @Override
    void close();

    /** A one-shot cancellation handle. */
    @FunctionalInterface
    interface Cancellation {
        /** Prevents a callback that has not started, without interrupting running work.
         * @return true only on the call that prevents a still-pending callback */
        boolean cancel();
    }

    /**
     * Converts a nonnegative delay without truncation or overflow.
     * @param delay requested delay
     * @return nanoseconds
     */
    static long delayNanos(Duration delay) {
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) throw new IllegalArgumentException("delay must be nonnegative");
        try {
            return delay.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("delay must fit in signed nanoseconds", overflow);
        }
    }
}
