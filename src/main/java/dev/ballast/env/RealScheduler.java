package dev.ballast.env;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Single-threaded host scheduler; callback failures are reported to the supplied owner. */
public final class RealScheduler implements Scheduler {
    private final ScheduledThreadPoolExecutor executor;
    private final Set<Event> pending = ConcurrentHashMap.newKeySet();
    private final Consumer<Throwable> failureHandler;
    private boolean closed;

    /**
     * Creates a scheduler with a daemon worker.
     * @param failureHandler recipient of callback failures; must not throw
     */
    public RealScheduler(Consumer<Throwable> failureHandler) {
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        executor = new ScheduledThreadPoolExecutor(1, callback -> {
            var thread = new Thread(callback, "ballast-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @Override
    public synchronized Cancellation schedule(Duration delay, Runnable callback) {
        long nanos = Scheduler.delayNanos(delay);
        Objects.requireNonNull(callback, "callback");
        if (closed) throw new IllegalStateException("scheduler is closed");
        var event = new Event(callback);
        pending.add(event);
        event.future = executor.schedule(event, nanos, TimeUnit.NANOSECONDS);
        return event;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        pending.forEach(Event::cancel);
        executor.shutdown();
    }

    private final class Event implements Runnable, Cancellation {
        private final Runnable callback;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private volatile ScheduledFuture<?> future;

        private Event(Runnable callback) { this.callback = callback; }

        @Override
        public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            try {
                callback.run();
            } catch (Throwable failure) {
                failureHandler.accept(failure);
            } finally {
                pending.remove(this);
            }
        }

        @Override
        public boolean cancel() {
            if (!claimed.compareAndSet(false, true)) return false;
            if (future != null) future.cancel(false);
            pending.remove(this);
            return true;
        }
    }
}
