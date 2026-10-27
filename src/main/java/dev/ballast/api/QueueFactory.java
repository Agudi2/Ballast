package dev.ballast.api;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Provider contract for creating or recovering a queue. Stage 0 supplies no concrete provider.
 * Implementations bind their environment adapters without adding I/O to the queue's value types.
 */
@FunctionalInterface
public interface QueueFactory {
    /**
     * Opens an exclusively owned local data directory, creating its queue if necessary.
     *
     * <p>An implementation must validate supported storage behavior, recover durable state, and
     * durably advance its startup epoch before returning. Pre-restart tokens are invalid; recovered
     * lease reservations wait for their reconstructed remaining timeout before redelivery.
     *
     * @param dataDirectory directory containing this queue's persistent identity and state
     * @return an open queue ready to accept operations
     * @throws NullPointerException if dataDirectory is null
     * @throws IOException if ownership, recovery, storage support, or initialization fails
     */
    JobQueue open(Path dataDirectory) throws IOException;
}
