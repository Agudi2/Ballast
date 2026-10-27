package dev.ballast.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Durable identity of a job within a queue's stored identity.
 *
 * @param queueId identity stored in the owning queue directory
 * @param sequence positive, non-reused job sequence within that queue
 */
public record JobId(UUID queueId, long sequence) {
    /**
     * Constructs a job identity.
     *
     * @throws NullPointerException if queueId is null
     * @throws IllegalArgumentException if sequence is not positive
     */
    public JobId {
        Objects.requireNonNull(queueId, "queueId");
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
    }
}
