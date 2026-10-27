package dev.ballast.simulation;

import static org.junit.jupiter.api.Assertions.*;

import dev.ballast.api.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReferenceQueueTest {
    private static final UUID ID = new UUID(1, 2);
    private static final Duration TIMEOUT = Duration.ofMillis(100);

    @Test
    void eligibilityPriorityAndSequenceAreIndependent() {
        var clock = new VirtualClock(1000);
        var model = new ReferenceQueue(ID, clock);
        model.enqueue(request(-10, 2000));
        var first = model.enqueue(request(1, 1000));
        var second = model.enqueue(request(1, 1000));
        assertEquals(first, model.lease(TIMEOUT).orElseThrow().jobId());
        assertEquals(second, model.lease(TIMEOUT).orElseThrow().jobId());
        assertTrue(model.lease(TIMEOUT).isEmpty());
    }

    @Test
    void exactExpiryAndStaleOrDuplicateAcknowledgements() {
        var clock = new VirtualClock(0);
        var model = new ReferenceQueue(ID, clock);
        model.enqueue(request(0, 0));
        var old = model.lease(TIMEOUT).orElseThrow();
        clock.advanceNanos(100_000_000);
        assertEquals(AckResult.NOT_APPLIED, model.acknowledge(old.jobId(), old.token()));
        var fresh = model.lease(TIMEOUT).orElseThrow();
        assertNotEquals(old.token(), fresh.token());
        assertEquals(AckResult.NOT_APPLIED, model.acknowledge(old.jobId(), old.token()));
        assertEquals(AckResult.APPLIED, model.acknowledge(fresh.jobId(), fresh.token()));
        assertEquals(AckResult.NOT_APPLIED, model.acknowledge(fresh.jobId(), fresh.token()));
        assertEquals(0, model.liveCount());
    }

    @Test
    void wallJumpsDoNotExpireLiveLeasesOrDemotePromotedJobs() {
        var clock = new VirtualClock(1000);
        var model = new ReferenceQueue(ID, clock);
        model.enqueue(request(0, 1000));
        model.enqueue(request(0, 1000));
        var lease = model.lease(TIMEOUT).orElseThrow();
        clock.jumpWallMillis(10000);
        assertEquals(AckResult.APPLIED, model.acknowledge(lease.jobId(), lease.token()));
        clock.jumpWallMillis(-20000);
        assertTrue(model.lease(TIMEOUT).isPresent());
    }

    @Test
    void restartFencesOldTokensAndKeepsPreviouslyGrantedJobsEligible() {
        var clock = new VirtualClock(1000);
        var model = new ReferenceQueue(ID, clock);
        model.enqueue(request(0, 1000));
        var old = model.lease(TIMEOUT).orElseThrow();
        clock.advanceNanos(100_000_000);
        model.refresh();
        var after = new VirtualClock(800);
        var recovered = ReferenceQueue.recover(model.snapshot(), after);
        assertEquals(AckResult.NOT_APPLIED, recovered.acknowledge(old.jobId(), old.token()));
        assertTrue(recovered.lease(TIMEOUT).isEmpty());
        after.advanceNanos(100_000_000);
        var next = recovered.lease(TIMEOUT).orElseThrow();
        assertEquals(old.jobId(), next.jobId());
        assertTrue(next.token().startupEpoch() > old.token().startupEpoch());
        assertEquals(900, after.wallTimeMillis());
    }

    @Test
    void restartRecomputesNeverGrantedEligibilityAndClampsOverflowSafely() {
        var clock = new VirtualClock(1000);
        var model = new ReferenceQueue(ID, clock);
        model.enqueue(request(0, 1000));
        var recovered = ReferenceQueue.recover(model.snapshot(), new VirtualClock(800));
        assertTrue(recovered.lease(TIMEOUT).isEmpty());
        var old = model.lease(TIMEOUT).orElseThrow();
        var extreme = new VirtualClock(Long.MIN_VALUE);
        var clamped = ReferenceQueue.recover(model.snapshot(), extreme);
        assertTrue(clamped.lease(TIMEOUT).isEmpty());
        extreme.advanceNanos(100_000_000);
        assertEquals(AckResult.NOT_APPLIED, clamped.acknowledge(old.jobId(), old.token()));
        assertEquals(1, clamped.liveCount());
        clamped.assertInvariants();
    }

    @Test
    void invalidDurationAndDeadlineOverflowDoNotMutateModel() {
        var clock = new VirtualClock(Long.MAX_VALUE);
        var model = new ReferenceQueue(ID, clock);
        model.enqueue(request(0, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> model.lease(Duration.ZERO));
        assertThrows(ArithmeticException.class, () -> model.lease(TIMEOUT));
        assertEquals(1, model.liveCount());
        clock.jumpWallMillis(-100);
        assertTrue(model.lease(TIMEOUT).isPresent());
    }

    private static EnqueueRequest request(int priority, long readyAt) {
        return new EnqueueRequest(new byte[] {1, 2}, priority, Instant.ofEpochMilli(readyAt));
    }
}
