package dev.ballast.simulation;

import dev.ballast.env.Scheduler;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;

/** Stable (deadline, registration sequence) event scheduling on a virtual clock. */
public final class DeterministicScheduler implements Scheduler {
    private final VirtualClock clock;
    private final PriorityQueue<Event> events = new PriorityQueue<>(
            Comparator.comparingLong((Event event) -> event.deadline).thenComparingLong(event -> event.sequence));
    private long nextSequence;
    private boolean closed;
    private boolean advancing;

    public DeterministicScheduler(VirtualClock clock) { this.clock = Objects.requireNonNull(clock); }

    @Override
    public Cancellation schedule(Duration delay, Runnable callback) {
        long nanos = Scheduler.delayNanos(delay);
        Objects.requireNonNull(callback, "callback");
        if (closed) throw new IllegalStateException("scheduler is closed");
        long deadline = Math.addExact(clock.elapsedNanos(), nanos);
        long sequence = Math.incrementExact(nextSequence);
        var event = new Event(deadline, sequence, callback);
        nextSequence = sequence;
        events.add(event);
        return event;
    }

    /** Runs all due callbacks, then advances to the requested elapsed reading. */
    public void advanceTo(long targetNanos) {
        if (closed) throw new IllegalStateException("scheduler is closed");
        if (advancing) throw new IllegalStateException("recursive time advancement is not allowed");
        if (targetNanos < clock.elapsedNanos()) throw new IllegalArgumentException("time cannot move backward");
        advancing = true;
        try {
            while (!events.isEmpty() && events.peek().deadline <= targetNanos) {
                Event event = events.remove();
                clock.advanceNanos(Math.max(0, event.deadline - clock.elapsedNanos()));
                if (event.pending) {
                    event.pending = false;
                    event.callback.run();
                }
            }
            clock.advanceNanos(Math.max(0, targetNanos - clock.elapsedNanos()));
        } finally {
            advancing = false;
        }
    }

    @Override
    public void close() {
        closed = true;
        events.forEach(event -> event.pending = false);
        events.clear();
    }

    private final class Event implements Cancellation {
        private final long deadline;
        private final long sequence;
        private final Runnable callback;
        private boolean pending = true;

        private Event(long deadline, long sequence, Runnable callback) {
            this.deadline = deadline;
            this.sequence = sequence;
            this.callback = callback;
        }

        @Override
        public boolean cancel() {
            if (!pending) return false;
            pending = false;
            events.remove(this);
            return true;
        }
    }
}
