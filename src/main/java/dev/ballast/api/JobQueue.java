package dev.ballast.api;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Contract for a thread-safe, single-directory, at-least-once job queue.
 *
 * <p>Implementations serialize state transitions. A successful enqueue, grant, or applied ACK is
 * durably synced before returning. An IOException or a missing response leaves the mutation's
 * outcome uncertain: retrying an enqueue can create another job. After an operational I/O failure,
 * the instance rejects all operations except close; recovery requires reopening the queue.
 *
 * <p>Only eligible jobs compete for delivery. Ordering is ascending priority, then readyAt, then
 * durable job sequence. Repeated delivery is permitted; worker side effects are not exactly-once.
 * This interface declares the contract; Stage 0 does not supply a queue implementation.
 */
public interface JobQueue extends AutoCloseable {
    /**
     * Durably adds one job. An enqueue retry is a new submission, not a deduplication request.
     *
     * @param request validated payload, priority, and availability time
     * @return the durable, non-reused job identity
     * @throws NullPointerException if request is null
     * @throws IllegalStateException if this instance is closed or failed
     * @throws IOException if storage fails; the job may nevertheless have persisted
     */
    JobId enqueue(EnqueueRequest request) throws IOException;

    /**
     * Grants the next eligible job without waiting for work to become available.
     *
     * <p>The grant captures wall and elapsed time at its serialized transition, and the visibility
     * countdown starts there. Durability latency consumes lease time. Expiry occurs when elapsed
     * time is greater than or equal to the requested duration, regardless of timer callback delay.
     *
     * @param visibilityTimeout whole milliseconds from 1 ms through 7 days, inclusive
     * @return a durably recorded grant, or empty if no eligible unleased job exists
     * @throws NullPointerException if visibilityTimeout is null
     * @throws IllegalArgumentException if the timeout violates {@link QueueLimits}
     * @throws IllegalStateException if this instance is closed or failed
     * @throws IOException if storage fails; a grant may nevertheless have persisted
     */
    Optional<Lease> lease(Duration visibilityTimeout) throws IOException;

    /**
     * Acknowledges the currently valid grant for a job.
     *
     * <p>Validity is checked at the serialized transition. A valid ACK may return after the lease's
     * deadline once its durable sync completes. Unknown jobs, duplicate ACKs, expired/superseded
     * tokens, tokens from an earlier startup, or mismatched queue identities return NOT_APPLIED.
     * Only one call can apply a job's terminal transition; repeats cause no additional effect.
     *
     * @param jobId identity of the job to acknowledge
     * @param token identity of its lease grant
     * @return APPLIED after durable acknowledgement, or NOT_APPLIED when no transition occurred
     * @throws NullPointerException if either argument is null
     * @throws IllegalStateException if this instance is closed or failed
     * @throws IOException if storage fails; the ACK may nevertheless have persisted
     */
    AckResult acknowledge(JobId jobId, LeaseToken token) throws IOException;

    /**
     * Releases this instance's resources and directory ownership.
     *
     * <p>Closing prevents new operations and settles earlier serialized operations. It does not
     * acknowledge leased work. Repeated calls are harmless, and close remains available after an
     * operational failure. The instance remains closed even if resource cleanup reports an error.
     *
     * @throws IOException if resource cleanup fails
     */
    @Override
    void close() throws IOException;
}
