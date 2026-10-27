package dev.ballast.api;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable data submitted for a new job.
 *
 * @param payload bytes to deliver; copied on construction and access, at most one mebibyte
 * @param priority scheduling priority; smaller signed integer values run first
 * @param readyAt wall-clock availability timestamp, exactly representable in epoch milliseconds
 */
public record EnqueueRequest(byte[] payload, int priority, Instant readyAt) {
    /**
     * Constructs and validates a request. Empty payloads and past timestamps are valid.
     *
     * @throws NullPointerException if payload or readyAt is null
     * @throws IllegalArgumentException if the payload is too large or readyAt cannot be represented
     *         as a signed count of whole milliseconds since the epoch
     */
    public EnqueueRequest {
        QueueLimits.validateReadyAt(readyAt);
        payload = QueueLimits.copyPayload(payload);
    }

    /**
     * Returns a copy of the payload.
     *
     * @return an independent copy of the submitted bytes
     */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EnqueueRequest request
                && priority == request.priority
                && readyAt.equals(request.readyAt)
                && Arrays.equals(payload, request.payload);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(priority, readyAt) + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "EnqueueRequest[payloadBytes=" + payload.length
                + ", priority=" + priority + ", readyAt=" + readyAt + "]";
    }
}
