# Ballast
# Ballast

A crash-safe job queue in Java, tested by a deterministic simulator that replays crashes, torn writes, and clock jumps from a single seed.

> **Status:** in progress. Results are TBD until measured.

## Why

Queue bugs depend on timing: a worker crashes before acking, a write is half done, a timeout fires twice. Ballast runs inside a simulated clock, disk, and scheduler driven by one seed, so every failure can be replayed exactly.

## Guarantees

- At-least-once delivery
- Durable once `enqueue` returns
- No job acked twice
- Leased jobs return to the queue after a visibility timeout

Out of scope: replication, clustering, exactly-once delivery.

## Design

- **Storage:** append-only WAL with per-record CRC, checkpoints, compaction
- **Ready queue:** binary heap on `(priority, ready_at, seq)`
- **Leases:** hash map plus timing wheel
- **Recovery:** load checkpoint, replay log, truncate at first bad CRC
- **IO:** `Clock`, `Disk`, `Scheduler` interfaces, real in production, simulated in tests

Tradeoffs are recorded in `docs/decisions/`.

## Simulation

One seed controls the operation mix, fault injection (crashes at write boundaries, torn writes, lost fsyncs, clock jumps), and worker ordering. A checker compares the queue to a reference model after every step. Failing runs print their seed:

```bash
./gradlew simulate --seed 8812934 --steps 100000
```

Every bug found is logged in `docs/bugs.md` with its seed, root cause, and fix.

## Run

Java 21, Gradle.

```bash
./gradlew test
./gradlew simulate --runs 1000
./gradlew bench
```

## Results

| Metric | Value |
|---|---|
| Simulated runs / steps | TBD |
| Bugs found | TBD |
| Throughput, fsync per enqueue / group commit | TBD / TBD jobs/s |
| Recovery time, TBD-record log | TBD ms |

Benchmark setup is in `bench/README.md`.

## References

- Will Wilson, "Testing Distributed Systems w/ Deterministic Simulation," Strange Loop 2014
- TigerBeetle VOPR
- Pillai et al., "All File Systems Are Not Created Equal," OSDI 2014
