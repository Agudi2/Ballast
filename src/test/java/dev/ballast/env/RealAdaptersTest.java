package dev.ballast.env;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RealAdaptersTest {
    @Test
    void clockSamplesHostSourcesWithoutPersistableElapsedOrigin() {
        var clock = new SystemClock();
        long wallBefore = System.currentTimeMillis();
        long wall = clock.wallTimeMillis();
        long wallAfter = System.currentTimeMillis();
        assertTrue(wall >= Math.min(wallBefore, wallAfter) && wall <= Math.max(wallBefore, wallAfter));
        long before = System.nanoTime();
        long actual = clock.elapsedNanos();
        long after = System.nanoTime();
        assertTrue(actual - before >= 0 && after - actual >= 0);
    }

    @Test
    void schedulerReportsFailuresAndCancellationCannotClaimRunningWork() throws Exception {
        var failure = new AtomicReference<Throwable>();
        var failed = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var scheduler = new RealScheduler(error -> { failure.set(error); failed.countDown(); });
        try (scheduler) {
            var delayed = scheduler.schedule(Duration.ofDays(1), () -> fail("cancelled callback ran"));
            assertTrue(delayed.cancel());
            assertFalse(delayed.cancel());
            var running = scheduler.schedule(Duration.ZERO, () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test wait expired");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                throw new IllegalArgumentException("expected callback failure");
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(running.cancel());
            } finally {
                release.countDown();
            }
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, failure.get());
        }
        assertThrows(IllegalStateException.class, () -> scheduler.schedule(Duration.ZERO, () -> {}));
    }
}
