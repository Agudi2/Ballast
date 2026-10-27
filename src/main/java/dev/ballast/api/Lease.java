package dev.ballast.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable delivery from a lease grant.
 *
 * <p>The visibility countdown starts at the serialized grant, before the durability sync. The
 * original timeout is not the time remaining when this value reaches the caller. A token can expire
 * before the caller receives it, and every token becomes invalid when its queue is reopened.
 *
 * @param jobId identity of the delivered job
 * @param token identity of this grant, scoped to the same queue as jobId
 * @param payload delivered bytes, copied on construction and access
 * @param priority original scheduling priority
 * @param readyAt original wall-clock availability timestamp
 * @param visibilityTimeout original grant timeout, not the remaining timeout
 */
public record Lease(
        JobId jobId,
        LeaseToken token,
        byte[] payload,
        int priority,
        Instant readyAt,
        Duration visibilityTimeout) {
    /**
     * Constructs and validates a delivery value.
     *
     * @throws NullPointerException if any reference component is null
     * @throws IllegalArgumentException if queue identities differ or payload/time limits are violated
     */
    public Lease {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(token, "token");
        if (!jobId.queueId().equals(token.queueId())) {
            throw new IllegalArgumentException("jobId and token must belong to the same queue");
        }
        QueueLimits.validateReadyAt(readyAt);
        QueueLimits.validateVisibilityTimeout(visibilityTimeout);
        payload = QueueLimits.copyPayload(payload);
    }

    /**
     * Returns a copy of the payload.
     *
     * @return an independent copy of the delivered bytes
     */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Lease lease
                && jobId.equals(lease.jobId)
                && token.equals(lease.token)
                && priority == lease.priority
                && readyAt.equals(lease.readyAt)
                && visibilityTimeout.equals(lease.visibilityTimeout)
                && Arrays.equals(payload, lease.payload);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(jobId, token, priority, readyAt, visibilityTimeout)
                + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "Lease[jobId=" + jobId + ", token=" + token + ", payloadBytes=" + payload.length
                + ", priority=" + priority + ", readyAt=" + readyAt
                + ", visibilityTimeout=" + visibilityTimeout + "]";
    }
}
