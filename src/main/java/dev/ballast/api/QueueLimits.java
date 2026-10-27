package dev.ballast.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Input limits shared by queue requests and implementations. */
public final class QueueLimits {
    /** Maximum job payload, in bytes; empty payloads are valid. */
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;

    /** Maximum visibility timeout, inclusive. */
    public static final Duration MAX_VISIBILITY_TIMEOUT = Duration.ofDays(7);

    private QueueLimits() {}

    /**
     * Validates a visibility timeout without rounding it.
     *
     * @param visibilityTimeout duration from one millisecond through seven days, inclusive
     * @throws NullPointerException if the duration is null
     * @throws IllegalArgumentException if it is outside the limits or has sub-millisecond precision
     */
    public static void validateVisibilityTimeout(Duration visibilityTimeout) {
        Objects.requireNonNull(visibilityTimeout, "visibilityTimeout");
        if (visibilityTimeout.isNegative() || visibilityTimeout.isZero()
                || visibilityTimeout.compareTo(MAX_VISIBILITY_TIMEOUT) > 0
                || visibilityTimeout.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException(
                    "visibilityTimeout must be whole milliseconds between 1 ms and 7 days");
        }
    }

    static byte[] copyPayload(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload must not exceed " + MAX_PAYLOAD_BYTES + " bytes");
        }
        return payload.clone();
    }

    static void validateReadyAt(Instant readyAt) {
        Objects.requireNonNull(readyAt, "readyAt");
        if (readyAt.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("readyAt must have whole-millisecond precision");
        }
        try {
            readyAt.toEpochMilli();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("readyAt must fit in signed epoch milliseconds", overflow);
        }
    }
}
