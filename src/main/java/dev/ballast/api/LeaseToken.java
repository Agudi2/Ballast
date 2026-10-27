package dev.ballast.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of one lease grant. It is a correctness token, not an authentication credential.
 *
 * @param queueId identity stored in the owning queue directory
 * @param startupEpoch positive durable startup epoch; earlier epochs become invalid on reopen
 * @param sequence positive, non-reused grant sequence within the epoch
 */
public record LeaseToken(UUID queueId, long startupEpoch, long sequence) {
    /**
     * Constructs a lease token.
     *
     * @throws NullPointerException if queueId is null
     * @throws IllegalArgumentException if either counter is not positive
     */
    public LeaseToken {
        Objects.requireNonNull(queueId, "queueId");
        if (startupEpoch <= 0 || sequence <= 0) {
            throw new IllegalArgumentException("startupEpoch and sequence must be positive");
        }
    }
}
