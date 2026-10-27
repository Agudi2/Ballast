package dev.ballast.simulation;

import dev.ballast.api.AckResult;
import dev.ballast.api.EnqueueRequest;
import dev.ballast.api.JobId;
import dev.ballast.api.Lease;
import dev.ballast.api.LeaseToken;
import dev.ballast.api.QueueLimits;
import dev.ballast.env.Clock;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Independent specification model using scans. Not a JobQueue implementation: no WAL, real disk,
 * timing wheel, or durability claims. Every mutation here is an idealized completed operation.
 */
public final class ReferenceQueue {
    private final UUID queueId;
    private final Clock clock;
    private final Map<JobId, State> jobs = new LinkedHashMap<>();
    private long epoch = 1;
    private long lastJobSequence;
    private long lastLeaseSequence;

    public ReferenceQueue(UUID queueId, Clock clock) {
        this.queueId = Objects.requireNonNull(queueId);
        this.clock = Objects.requireNonNull(clock);
    }

    public JobId enqueue(EnqueueRequest request) {
        Objects.requireNonNull(request, "request");
        long sequence = Math.incrementExact(lastJobSequence);
        var id = new JobId(queueId, sequence);
        var state = new State(id, request);
        state.eligible = request.readyAt().toEpochMilli() <= clock.wallTimeMillis();
        jobs.put(id, state);
        lastJobSequence = sequence;
        return id;
    }

    public Optional<Lease> lease(Duration visibilityTimeout) {
        QueueLimits.validateVisibilityTimeout(visibilityTimeout);
        refresh();
        State next = jobs.values().stream().filter(state -> state.eligible && !state.reserved)
                .min(Comparator.comparingInt((State state) -> state.request.priority())
                        .thenComparingLong(state -> state.request.readyAt().toEpochMilli())
                        .thenComparingLong(state -> state.id.sequence())).orElse(null);
        if (next == null) return Optional.empty();
        long sequence = Math.incrementExact(lastLeaseSequence);
        long expiry = Math.addExact(clock.wallTimeMillis(), visibilityTimeout.toMillis());
        var token = new LeaseToken(queueId, epoch, sequence);
        next.lastGrant = token;
        next.wallExpiry = expiry;
        next.originalTimeoutMillis = visibilityTimeout.toMillis();
        next.elapsedStart = clock.elapsedNanos();
        next.waitNanos = visibilityTimeout.toNanos();
        next.reserved = true;
        lastLeaseSequence = sequence;
        return Optional.of(new Lease(next.id, token, next.request.payload(), next.request.priority(),
                next.request.readyAt(), visibilityTimeout));
    }

    public AckResult acknowledge(JobId jobId, LeaseToken token) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(token, "token");
        refresh();
        State state = jobs.get(jobId);
        if (state == null || !state.reserved || token.startupEpoch() != epoch
                || !token.equals(state.lastGrant)) return AckResult.NOT_APPLIED;
        jobs.remove(jobId);
        return AckResult.APPLIED;
    }

    public void refresh() {
        for (State state : jobs.values()) {
            if (state.reserved && clock.elapsedNanos() - state.elapsedStart >= state.waitNanos) {
                state.reserved = false;
            }
            if (state.lastGrant != null || state.request.readyAt().toEpochMilli() <= clock.wallTimeMillis()) {
                state.eligible = true;
            }
        }
    }

    public int liveCount() { return jobs.size(); }

    /** Idealized persisted state; never a copy of production queue recovery output. */
    public Snapshot snapshot() {
        var stored = new ArrayList<StoredJob>();
        for (State state : jobs.values()) {
            stored.add(new StoredJob(state.id, state.request, state.lastGrant,
                    state.wallExpiry, state.originalTimeoutMillis));
        }
        return new Snapshot(queueId, epoch, lastJobSequence, List.copyOf(stored));
    }

    public static ReferenceQueue recover(Snapshot snapshot, Clock clock) {
        Objects.requireNonNull(snapshot);
        var model = new ReferenceQueue(snapshot.queueId(), clock);
        model.epoch = Math.incrementExact(snapshot.epoch());
        model.lastJobSequence = snapshot.lastJobSequence();
        for (StoredJob stored : snapshot.jobs()) {
            var state = new State(stored.id(), stored.request());
            state.lastGrant = stored.lastGrant();
            state.wallExpiry = stored.wallExpiry();
            state.originalTimeoutMillis = stored.originalTimeoutMillis();
            state.eligible = state.lastGrant != null
                    || state.request.readyAt().toEpochMilli() <= clock.wallTimeMillis();
            if (state.lastGrant != null) {
                // Deliberately simple independent arithmetic, including extreme timestamps.
                long remaining = BigInteger.valueOf(state.wallExpiry)
                        .subtract(BigInteger.valueOf(clock.wallTimeMillis()))
                        .max(BigInteger.ZERO).min(BigInteger.valueOf(state.originalTimeoutMillis)).longValueExact();
                state.waitNanos = Math.multiplyExact(remaining, 1_000_000);
                state.elapsedStart = clock.elapsedNanos();
                state.reserved = remaining > 0;
            }
            model.jobs.put(state.id, state);
        }
        model.assertInvariants();
        return model;
    }

    public void assertInvariants() {
        refresh();
        for (State state : jobs.values()) {
            if (!state.id.queueId().equals(queueId) || state.id.sequence() > lastJobSequence
                    || (state.reserved && (!state.eligible || state.lastGrant == null || state.waitNanos <= 0))
                    || (state.lastGrant != null && (!state.lastGrant.queueId().equals(queueId)
                        || state.lastGrant.startupEpoch() > epoch))) {
                throw new IllegalStateException("reference state invariant failed for " + state.id);
            }
        }
    }

    /** Canonical logical state for trace checksums; iteration never depends on hash-map order. */
    public String stateSignature() {
        refresh();
        var text = new StringBuilder().append(queueId).append('/').append(epoch).append('/')
                .append(lastJobSequence).append('/').append(lastLeaseSequence);
        for (State state : jobs.values()) {
            text.append('|').append(state.id.sequence()).append(':').append(state.request.priority())
                    .append(':').append(state.request.readyAt().toEpochMilli()).append(':')
                    .append(HexFormat.of().formatHex(state.request.payload())).append(':')
                    .append(state.eligible).append(':').append(state.reserved).append(':')
                    .append(state.lastGrant).append(':').append(state.wallExpiry).append(':')
                    .append(state.originalTimeoutMillis).append(':').append(state.elapsedStart)
                    .append(':').append(state.waitNanos);
        }
        return text.toString();
    }

    public record Snapshot(UUID queueId, long epoch, long lastJobSequence, List<StoredJob> jobs) {
        public Snapshot {
            Objects.requireNonNull(queueId);
            jobs = List.copyOf(jobs);
            if (epoch <= 0 || lastJobSequence < 0) throw new IllegalArgumentException("invalid model counters");
        }
    }

    public record StoredJob(JobId id, EnqueueRequest request, LeaseToken lastGrant,
                            long wallExpiry, long originalTimeoutMillis) {}

    private static final class State {
        private final JobId id;
        private final EnqueueRequest request;
        private LeaseToken lastGrant;
        private long wallExpiry;
        private long originalTimeoutMillis;
        private long elapsedStart;
        private long waitNanos;
        private boolean reserved;
        private boolean eligible;

        private State(JobId id, EnqueueRequest request) { this.id = id; this.request = request; }
    }
}
