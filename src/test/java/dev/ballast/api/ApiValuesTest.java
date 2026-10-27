package dev.ballast.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApiValuesTest {
    private static final UUID QUEUE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final JobId JOB_ID = new JobId(QUEUE_ID, 1);
    private static final LeaseToken TOKEN = new LeaseToken(QUEUE_ID, 1, 1);

    @Test
    void enqueueRequestOwnsItsPayloadAndUsesContentEquality() {
        byte[] original = {1, 2, 3};
        var request = new EnqueueRequest(original, -10, Instant.EPOCH);
        original[0] = 9;
        request.payload()[1] = 9;

        var equivalent = new EnqueueRequest(new byte[] {1, 2, 3}, -10, Instant.EPOCH);
        assertArrayEquals(new byte[] {1, 2, 3}, request.payload());
        assertEquals(equivalent, request);
        assertEquals(equivalent.hashCode(), request.hashCode());
        assertNotEquals(new EnqueueRequest(new byte[] {1, 2, 4}, -10, Instant.EPOCH), request);
    }

    @Test
    void leaseOwnsItsPayloadAndUsesContentEquality() {
        byte[] original = {4, 5};
        var lease = lease(original);
        original[0] = 9;
        lease.payload()[1] = 9;

        var equivalent = lease(new byte[] {4, 5});
        assertArrayEquals(new byte[] {4, 5}, lease.payload());
        assertEquals(equivalent, lease);
        assertEquals(equivalent.hashCode(), lease.hashCode());
        assertNotEquals(lease(new byte[] {4, 6}), lease);
    }

    @Test
    void payloadLimitIncludesEmptyAndExactlyOneMebibyte() {
        assertDoesNotThrow(() -> new EnqueueRequest(new byte[0], Integer.MIN_VALUE, Instant.EPOCH));
        assertDoesNotThrow(() -> new EnqueueRequest(
                new byte[QueueLimits.MAX_PAYLOAD_BYTES], Integer.MAX_VALUE, Instant.EPOCH));
        assertDoesNotThrow(() -> lease(new byte[QueueLimits.MAX_PAYLOAD_BYTES]));
        assertThrows(IllegalArgumentException.class, () -> new EnqueueRequest(
                new byte[QueueLimits.MAX_PAYLOAD_BYTES + 1], 0, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,
                () -> lease(new byte[QueueLimits.MAX_PAYLOAD_BYTES + 1]));
        assertThrows(NullPointerException.class, () -> new EnqueueRequest(null, 0, Instant.EPOCH));
        assertThrows(NullPointerException.class, () -> lease(null));
    }

    @Test
    void readyAtAcceptsFullEpochMillisecondRangeWithoutTruncation() {
        for (long millis : new long[] {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE}) {
            Instant readyAt = Instant.ofEpochMilli(millis);
            var request = new EnqueueRequest(new byte[0], 0, readyAt);
            assertEquals(readyAt, request.readyAt());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new EnqueueRequest(new byte[0], 0, Instant.EPOCH.plusNanos(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new EnqueueRequest(new byte[0], 0, Instant.EPOCH.minusNanos(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new EnqueueRequest(new byte[0], 0, Instant.MIN));
        assertThrows(IllegalArgumentException.class,
                () -> new EnqueueRequest(new byte[0], 0, Instant.ofEpochMilli(Long.MAX_VALUE).plusMillis(1)));
        assertThrows(NullPointerException.class,
                () -> new EnqueueRequest(new byte[0], 0, null));
    }

    @Test
    void visibilityTimeoutHasInclusiveMillisecondAndSevenDayBounds() {
        assertDoesNotThrow(() -> QueueLimits.validateVisibilityTimeout(Duration.ofMillis(1)));
        assertDoesNotThrow(() -> QueueLimits.validateVisibilityTimeout(Duration.ofDays(7)));
        for (Duration invalid : new Duration[] {
                Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1),
                Duration.ofMillis(1).plusNanos(1), Duration.ofDays(7).plusMillis(1),
                Duration.ofSeconds(Long.MAX_VALUE)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> QueueLimits.validateVisibilityTimeout(invalid), invalid.toString());
            assertThrows(IllegalArgumentException.class,
                    () -> new Lease(JOB_ID, TOKEN, new byte[0], 0, Instant.EPOCH, invalid));
        }
        assertThrows(NullPointerException.class, () -> QueueLimits.validateVisibilityTimeout(null));
    }

    @Test
    void identitiesRequireQueueScopeAndPositiveCounters() {
        assertThrows(NullPointerException.class, () -> new JobId(null, 1));
        assertThrows(NullPointerException.class, () -> new LeaseToken(null, 1, 1));
        for (long invalid : new long[] {Long.MIN_VALUE, -1, 0}) {
            assertThrows(IllegalArgumentException.class, () -> new JobId(QUEUE_ID, invalid));
            assertThrows(IllegalArgumentException.class, () -> new LeaseToken(QUEUE_ID, invalid, 1));
            assertThrows(IllegalArgumentException.class, () -> new LeaseToken(QUEUE_ID, 1, invalid));
        }
        assertDoesNotThrow(() -> new JobId(QUEUE_ID, Long.MAX_VALUE));
        assertDoesNotThrow(() -> new LeaseToken(QUEUE_ID, Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void leaseRejectsMismatchedQueueScopeAndInvalidFields() {
        var otherToken = new LeaseToken(UUID.fromString("00000000-0000-0000-0000-000000000002"), 1, 1);
        assertThrows(IllegalArgumentException.class,
                () -> new Lease(JOB_ID, otherToken, new byte[0], 0, Instant.EPOCH, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class,
                () -> new Lease(null, TOKEN, new byte[0], 0, Instant.EPOCH, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class,
                () -> new Lease(JOB_ID, null, new byte[0], 0, Instant.EPOCH, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new Lease(JOB_ID, TOKEN, new byte[0], 0, Instant.EPOCH.plusNanos(1), Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class,
                () -> new Lease(JOB_ID, TOKEN, new byte[0], 0, null, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class,
                () -> new Lease(JOB_ID, TOKEN, new byte[0], 0, Instant.EPOCH, null));
    }

    @Test
    void testsRunOnJava21() {
        assertEquals(21, Runtime.version().feature(), "The test launcher must use the Java 21 toolchain");
    }

    private static Lease lease(byte[] payload) {
        return new Lease(JOB_ID, TOKEN, payload, 0, Instant.EPOCH, Duration.ofSeconds(30));
    }
}
