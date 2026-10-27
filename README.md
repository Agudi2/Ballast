# Ballast

A crash-safe job queue in Java, tested by a deterministic simulator that replays crashes, torn writes, and clock jumps from a single seed.

> **Status:** Stages 0–1 are complete: Java 21 build, API contracts, environment adapters, and a deterministic reference-model runner. The production queue, WAL, and fault checker are not implemented yet. Next: Stage 2, in-memory queue and leases.

See the [implementation plan](docs/implementation-plan.md) for stages, dependencies, and acceptance checks.

## Why

Queue bugs depend on timing: a worker crashes before acking, a write is half done, a timeout fires twice. Ballast runs inside a simulated clock, disk, and scheduler driven by one seed, so every failure can be replayed exactly.

## Target guarantees

- At-least-once delivery
- Durable once `enqueue` returns
- No job acked twice
- Leased jobs return to the queue after a visibility timeout

Out of scope: replication, clustering, exactly-once delivery.

## Planned design

- **Storage:** append-only WAL with per-record CRC, checkpoints, compaction
- **Ready queue:** binary heap on `(priority, ready_at, seq)`
- **Leases:** hash map plus timing wheel
- **Recovery:** load checkpoint, replay log, truncate at first bad CRC
- **IO:** `Clock`, `Disk`, `Scheduler` interfaces, real in production, simulated in tests

Contracts and tradeoffs are recorded in [API and state machine](docs/decisions/0001-api-and-state-machine.md), [time and restart](docs/decisions/0002-time-and-restart.md), [durability and faults](docs/decisions/0003-durability-and-fault-model.md), and [environment and replay](docs/decisions/0004-environment-and-replay.md).

## Simulation

The Stage 1 runner uses one root seed to derive operation and scheduling streams. It exercises an independent reference model with four workers, delayed jobs, leases, duplicate/stale ACKs, and clock changes. **Every run is labeled `MODEL_ONLY`; it does not test a production queue or inject storage faults.** Later stages connect the queue and add crashes, torn writes, and failed syncs.

```bash
./gradlew simulate --seed 8812934 --steps 100000
./gradlew simulate --runs 1000
./gradlew simulate --seed 42 --steps 5000 --trace-dir build/simulation/seed-42
```

Defaults are seed 1, 1,000 steps, and one run. Traces are saved as `build/simulation/run-N.trace`, with seeds, configuration, source fingerprint, and every executed operation. Reusing a trace directory replaces matching files. Replay requires the same source/runtime and configuration; tests verify byte-identical traces across fresh Java processes.

Every bug found is logged in [docs/bugs.md](docs/bugs.md) with its reproducer, root cause, and fix.

## Run

Set `JAVA_HOME` to a Java 21 JDK. The checked-in wrapper downloads Gradle 9.2.1; no separate Gradle installation is required. The first build also downloads the pinned JUnit dependencies. Compilation and tests both use the Java 21 toolchain.

```bash
./gradlew test
./gradlew build
```

The 26 tests cover API values, clocks/scheduling, simulated disk persistence boundaries, model ordering/expiry/restart rules, separate-process replay, and CLI failures. `build` also creates the library, sources, and Javadoc jars. Test reports are written to `build/reports/tests/test/index.html`. Simulation classes are kept in a separate source set and are not shipped in the production library jar.

This checkout also has an ignored project-local JDK/cache. To use those without changing the system Java installation:

```bash
env JAVA_HOME="$PWD/.tools/jdk-21" GRADLE_USER_HOME="$PWD/.gradle-user-home" ./gradlew test
```

The local tools directory is not part of a fresh clone; use an installed Java 21 JDK there. The benchmark command remains planned for Stage 9:

```bash
./gradlew bench
```

## Results

| Metric | Value |
|---|---|
| Stage 1 reference-model acceptance runs / steps | 1,001 / 1,100,000; [verification](docs/stages/stage-1.md) |
| Production queue fault runs / steps | Not implemented |
| Bugs found | TBD |
| Throughput, fsync per enqueue / group commit | TBD / TBD jobs/s |
| Recovery time, TBD-record log | TBD ms |

Benchmark setup will be documented in `bench/README.md` in Stage 9.

## References

- Will Wilson, "Testing Distributed Systems w/ Deterministic Simulation," Strange Loop 2014
- TigerBeetle VOPR
- Pillai et al., "All File Systems Are Not Created Equal," OSDI 2014
