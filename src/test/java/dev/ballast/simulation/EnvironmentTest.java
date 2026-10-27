package dev.ballast.simulation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnvironmentTest {
    @Test
    void virtualTimeSeparatesWallJumpsAndElapsedTimeAndKeepsFractions() {
        var clock = new VirtualClock(100);
        clock.advanceNanos(500_000);
        clock.advanceNanos(500_000);
        assertEquals(101, clock.wallTimeMillis());
        assertEquals(1_000_000, clock.elapsedNanos());
        clock.jumpWallMillis(-500);
        assertEquals(-399, clock.wallTimeMillis());
        assertEquals(1_000_000, clock.elapsedNanos());
        clock.advanceElapsedNanos(1_000_000);
        assertEquals(-399, clock.wallTimeMillis());
        assertEquals(2_000_000, clock.elapsedNanos());
        assertThrows(IllegalArgumentException.class, () -> clock.advanceNanos(-1));
        var extreme = new VirtualClock(Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> extreme.advanceNanos(1_000_000));
        assertEquals(0, extreme.elapsedNanos(), "Overflow must not partially advance time");
    }

    @Test
    void schedulerOrdersEqualDeadlinesAndNestedEventsAndCancelsExactlyOnce() {
        var clock = new VirtualClock(0);
        var scheduler = new DeterministicScheduler(clock);
        var seen = new ArrayList<Integer>();
        scheduler.schedule(Duration.ofMillis(2), () -> {
            seen.add(1);
            scheduler.schedule(Duration.ZERO, () -> seen.add(4));
        });
        var cancelled = scheduler.schedule(Duration.ofMillis(1), () -> seen.add(99));
        var completed = scheduler.schedule(Duration.ofMillis(2), () -> seen.add(2));
        scheduler.schedule(Duration.ofMillis(2), () -> seen.add(3));
        assertTrue(cancelled.cancel());
        assertFalse(cancelled.cancel());
        scheduler.advanceTo(1_999_999);
        assertTrue(seen.isEmpty());
        scheduler.advanceTo(2_000_000);
        assertEquals(List.of(1, 2, 3, 4), seen);
        assertFalse(completed.cancel());
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.schedule(Duration.ofNanos(-1), () -> {}));
        scheduler.close();
        assertThrows(IllegalStateException.class, () -> scheduler.schedule(Duration.ZERO, () -> {}));
    }

    @Test
    void schedulerSurfacesCallbackFailuresWithoutReplayingTheCallback() {
        var clock = new VirtualClock(0);
        var scheduler = new DeterministicScheduler(clock);
        var seen = new ArrayList<Integer>();
        scheduler.schedule(Duration.ZERO, () -> { throw new IllegalStateException("callback"); });
        scheduler.schedule(Duration.ZERO, () -> seen.add(1));
        assertThrows(IllegalStateException.class, () -> scheduler.advanceTo(0));
        scheduler.advanceTo(0);
        assertEquals(List.of(1), seen);
    }

    @Test
    void diskSeparatesDurableBytesFromDurableDirectoryEntries() throws Exception {
        var disk = new MemoryDisk();
        disk.create("wal");
        disk.write("wal", 0, ByteBuffer.wrap(new byte[] {1, 2}));
        disk.syncFile("wal");
        disk.crash();
        assertTrue(disk.list().isEmpty(), "File sync alone does not publish its name");
        disk.create("wal");
        disk.syncDirectory();
        disk.write("wal", 0, ByteBuffer.wrap(new byte[] {1, 2}));
        disk.crash();
        assertEquals(0, disk.size("wal"), "Directory sync alone does not persist bytes");
        disk.write("wal", 0, ByteBuffer.wrap(new byte[] {1, 2}));
        disk.syncFile("wal");
        disk.write("wal", 1, ByteBuffer.wrap(new byte[] {9}));
        disk.crash();
        var result = ByteBuffer.allocate(4);
        assertEquals(2, disk.read("wal", 0, result));
        assertArrayEquals(new byte[] {1, 2, 0, 0}, result.array());
        assertEquals(-1, disk.read("wal", 2, ByteBuffer.allocate(1)));
        assertEquals(0, disk.read("wal", 2, ByteBuffer.allocate(0)));
    }

    @Test
    void renameAndDeletionSurviveOnlyAfterDirectorySync() throws Exception {
        var disk = new MemoryDisk();
        disk.create("old");
        disk.write("old", 0, ByteBuffer.wrap(new byte[] {1}));
        disk.syncFile("old");
        disk.syncDirectory();
        disk.move("old", "new");
        disk.crash();
        assertEquals(List.of("old"), disk.list());
        disk.move("old", "new");
        disk.syncDirectory();
        disk.crash();
        assertEquals(List.of("new"), disk.list());
        disk.delete("new");
        disk.crash();
        assertEquals(List.of("new"), disk.list());
        disk.delete("new");
        disk.syncDirectory();
        disk.crash();
        assertTrue(disk.list().isEmpty());
    }

    @Test
    void diskReplacementPreservesOldInodeUntilPublicationAndChecksBounds() throws Exception {
        var disk = new MemoryDisk();
        for (String name : List.of("old", "replacement")) {
            disk.create(name);
            disk.write(name, 0, ByteBuffer.wrap(new byte[] {(byte) name.length()}));
            disk.syncFile(name);
        }
        disk.syncDirectory();
        disk.move("replacement", "old");
        disk.crash();
        var bytes = ByteBuffer.allocate(1);
        disk.read("old", 0, bytes);
        assertEquals(3, bytes.get(0));
        assertThrows(IllegalArgumentException.class, () -> disk.create("../escape"));
        assertThrows(IllegalArgumentException.class, () -> disk.write("old", -1, ByteBuffer.allocate(1)));
        disk.truncate("old", 0);
        disk.syncFile("old");
        disk.crash();
        assertEquals(0, disk.size("old"));
        disk.close();
        assertThrows(IllegalStateException.class, disk::list);
    }
}
