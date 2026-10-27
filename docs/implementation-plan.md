# Ballast implementation plan

Status: Stages 0–1 complete. Stage 2 is next; Stages 2–9 have not started.

The project began with only `README.md`. Stages 0–1 now supply the build, API contracts, environment interfaces/adapters, and model-only simulator. This plan builds the Java 21 queue and its deterministic simulator together, then adds storage maintenance and measures performance. A stage is complete only when its acceptance checks pass; scaffolding or expected failing tests do not count as completed functionality.

## Scope and working approach

- One queue process owns one local data directory. Multiple callers and workers submit operations through one serialized state-transition path.
- Preserve the README's at-least-once delivery, durable enqueue, single terminal acknowledgement, and visibility timeout guarantees.
- Keep replication, clustering, exactly-once execution, networking, and a distributed worker service out of scope.
- Use a single Gradle project initially, with separate packages for queue logic, storage, environment adapters, simulation, and benchmarks.
- For each behavioral stage, first write contract tests or a reproducible failing scenario, then implement the smallest complete change, then run the stage checks and existing regressions.
- Inject `Clock`, `Disk`, and `Scheduler` from the beginning. The simulator must execute the production queue and storage code.
- Record consequential choices in `docs/decisions/`. Log actual bugs in `docs/bugs.md` with the seed or deterministic reproducer, root cause, fix, and regression test.

## Stage overview

| Stage | Deliverable | Completion gate |
|---|---|---|
| 0 | Build and behavioral contracts | Java 21 build works; API, time, durability, and fault assumptions are documented |
| 1 | Environment interfaces, reference model, simulator skeleton | Same seed and configuration produce the same ordered trace |
| 2 | In-memory queue, delayed jobs, leases | Queue behavior matches the independent model, including timeout boundaries |
| 3 | WAL format and disk adapters | Record validation, durable boundaries, partial writes, and tail repair pass |
| 4 | Durable queue and recovery | Completed enqueues and acknowledgements survive crashes; interrupted calls obey the contract |
| 5 | Checkpoints | Every interrupted checkpoint publication recovers a complete valid state |
| 6 | Compaction | Reclaiming storage cannot lose live jobs or resurrect acknowledged jobs |
| 7 | Full fault campaigns and regression workflow | Targeted crash schedules and seeded campaigns pass and replay exactly |
| 8 | Group commit and caller integration | Batching preserves the same durability guarantees under concurrency and faults |
| 9 | Benchmarks, documentation, measured results | README commands work and reported results have reproducible evidence |

Main sequence: **0 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9**. After Stage 1 freezes the environment interfaces, Stage 3's record codec and disk-adapter work can proceed alongside Stage 2. Stage 4 needs both. Simulation and regression coverage grow throughout; they are not deferred to Stage 7.

## Stage 0 — Build and freeze the contracts

**Work**

- Add the Gradle wrapper, Java 21 toolchain configuration, source/test layout, and test runner. Verify the selected compiler and runtime instead of relying on the machine's default Java installation.
- Define a small public API: open/close, enqueue, lease the next eligible job, and acknowledge with a job ID and lease token. Specify inputs, return values, size limits, validation, and I/O failure behavior.
- Describe the state machine: delayed → ready → leased → acknowledged; an expired lease makes the job eligible again.
- Document the proposed defaults below in API, clock, and durability decision records before implementation.

**Proposed defaults to make explicit**

| Topic | Contract |
|---|---|
| Ordering | Smaller numeric priority wins. Only eligible jobs compete; ties use `(priority, ready_at, seq)`. Sequence values remain stable through recovery. |
| Acknowledgement | At most one durable terminal ACK transition per job. A repeat call causes no new transition. An expired or superseded lease token cannot acknowledge a job. This does not promise exactly-once execution of a worker's external side effects. |
| Durability | Enqueue and a newly applied ACK report success only after their WAL records are durably synced. Persist a lease grant before exposing its token. |
| Interrupted calls | A call interrupted before its response may have taken effect. Recovery may retain a complete unacknowledged write. An I/O error must never be converted into a success response. Enqueue retry deduplication is outside the initial API. |
| Identity | Never reuse exposed job IDs or lease tokens after recovery or compaction. Persist the required sequence/generation information before exposure. |
| Time | `ready_at` is an absolute wall-clock timestamp. Within one process lifetime, a backward wall jump does not demote a job already promoted to ready. Recovery recalculates eligibility for jobs never durably leased; a durable lease grant preserves prior eligibility so its reservation expires directly to ready. Checkpoints retain that distinction. Use elapsed time for an active process's visibility timeout; expire at elapsed time greater than or equal to the timeout. |
| Restarted leases | Persist each lease's wall-clock expiry and original timeout. Reconstruct its reservation's remaining time as `clamp(expiry - wall_now, 0, original_timeout)`, then use elapsed time. Invalidate all pre-restart lease tokens using a new durable startup epoch; retain recovered reservations until their remaining time elapses before redelivery. Thus a backward jump cannot reactivate an expired token. A forward jump may expire a recovered reservation; a backward jump may delay it by at most one full timeout after restart. Workers must re-lease to obtain a valid token after restart. |
| Storage assumptions | Target documented local filesystem configurations that honor successful syncs. Model interrupted/failed syncs and loss of unsynced writes. A device discarding data after a successful durable sync is outside the guarantee. |
| Delivery progress | At-least-once progress assumes workers keep polling, time advances, storage eventually works, and the scheduling policy gives the job a turn. Strict priority can starve lower-priority jobs. |

**Acceptance**

- `./gradlew test` runs on the Java 21 toolchain with a basic build smoke check.
- Contract examples cover duplicate ACK, stale ACK after re-lease/restart, expiry equality, crash before response, and forward/backward clock jumps. Explicitly document that arbitrary clock jumps across restart do not preserve exact real elapsed time.
- The initial supported OS/filesystem and file/directory sync requirements are documented. Unsupported storage behavior is not silently accepted.

**Completed foundation**

- Gradle 9.2.1 wrapper with distribution checksum, pinned/locked JUnit 5.13.4 dependencies, Java 21 compiler/test toolchain, and API/source/Javadoc jars.
- Public contracts and immutable validated value types in `dev.ballast.api`. The eight API tests pass on Temurin 21.0.13. No queue implementation or fake successful storage behavior is supplied.
- Accepted decisions: [API/state machine](decisions/0001-api-and-state-machine.md), [clock/restart policy](decisions/0002-time-and-restart.md), and [durability/fault model](decisions/0003-durability-and-fault-model.md). Linux local ext4 is the first durable-adapter target, pending qualification in Stages 3–4; macOS currently supports development tests only.
- Validation: `./gradlew clean test build --write-locks` with the project-local Java 21 JDK and Gradle cache.

## Stage 1 — Build the test environment and independent model

**Work**

- Define `Clock` with distinct wall and elapsed time, `Disk` with explicit durability operations, and `Scheduler` with stable event ordering and cancellation.
- Add basic real clock/scheduler adapters behind the same interfaces; production concurrency and shutdown coverage expand in Stage 8.
- Keep real time, sleeping, filesystem access, and thread scheduling outside the queue core.
- Build a simple reference model using lists/maps and scans, not the queue's heap, timing wheel, or WAL implementation.
- Add a virtual clock, deterministic scheduler, seeded operation generator, and an in-memory disk skeleton. Define durable bytes and metadata separately from volatile state.
- Add the `simulate` task and options for seed, steps, and runs. Equal-time events need a deterministic tie-breaker; map iteration must not determine behavior.
- Record the root seed, derived per-run seed, configuration, simulator version, source revision, and operation trace. A seed reproduces a run for a fixed version/configuration.

**Acceptance**

- Two runs with identical inputs, including runs in separate JVM processes, produce the same trace and result.
- Advancing virtual time drives timers without real sleeps; wall time can jump independently of elapsed time.
- Invalid CLI options fail clearly, and the README's option syntax is accepted.
- Clearly label model-only runs until the production queue is connected in Stage 2.

**Completed foundation**

- Environment interfaces plus real clock/scheduler adapters; virtual clock, deterministic scheduler, and a disk skeleton with separate durable bytes/namespace state.
- Independent scan-based reference model with ordering, lease/ACK semantics, and idealized restart-policy tests.
- `simulate` task with seeds, step/run counts, explicit model-only output, failure exit codes, and reproducible traces including source fingerprints.
- All 26 tests pass on Java 21, including separate-process replay. The saved acceptance suite covers one 100,000-step run and 1,000 runs of 1,000 steps. See [Stage 1 verification](stages/stage-1.md) and [environment/replay decision](decisions/0004-environment-and-replay.md).
- This completes the simulator foundation, not the production queue or fault checker. Stage 2 implements the actual in-memory queue and compares it against this model.

## Stage 2 — Implement queue behavior in memory

**Work**

- Implement job state, priority ordering, stable sequence numbers, leasing, ACK validation, and lease expiry.
- Use a separate delayed-job index ordered by `ready_at`, then promote eligible jobs into the README's ready heap. A future high-priority job must not block an eligible lower-priority job.
- Use a lease hash map and timing wheel. Define tick precision, long timeouts, wheel wraparound, cancellation, and stale timer entries. Never expire early because of bucket rounding.
- Check expiry when processing lease/ACK operations, so a delayed timer callback cannot make an expired token valid.
- Compare the queue's externally visible behavior and normalized logical state against the reference model after each completed step.

**Acceptance**

- Tests cover empty queues, delayed availability, priority/FIFO ties, duplicate ACK, old tokens after re-lease, and exactly-at-deadline ACKs.
- Tests cover simultaneous expiry, wheel wraparound, large time advances, and wall-clock jumps.
- Fixed seeded runs match the model with no faults. There is no durability claim at this stage.

## Stage 3 — Implement the WAL and disk behavior

**Work**

- Specify a versioned, bounded record format containing framing, length, operation type, sequence information, payload, and CRC coverage. Reject impossible sizes before allocating memory.
- Implement encode/decode, append, full-write loops, sync, sequential scan, and truncation to the last valid record boundary.
- Add the real file adapter and complete the simulated disk's partial writes, unsynced-byte persistence/loss, sync completion/failure, and crash behavior. Include file creation and directory metadata durability.
- Insert deterministic fault points around storage operations so crashes can interrupt the same paths used in production.
- Treat a damaged uncommitted append tail as recoverable. Reject unsupported versions and structurally invalid histories. Do not claim that CRC truncation can repair arbitrary corruption of previously durable data without loss.

**Acceptance**

- Round trips cover every record type and boundary-sized payloads.
- Tests cover short/zero-progress writes, incomplete headers and payloads, invalid lengths, CRC failure, and unknown format versions.
- Enumerated small-record cut points recover only the valid prefix. No record after the first invalid record is replayed.
- Completed syncs survive simulated crashes; interrupted syncs can leave any permitted partial persistence. Reopening and appending after tail repair remains safe, including another crash during repair.

## Stage 4 — Connect durability, recovery, and the checker

**Work**

- Integrate enqueue, lease grant, ACK, and durable identity counters with the WAL. Publish responses only after their required durable boundary.
- On open, scan/replay the log, repair a damaged tail, reconstruct ready/delayed indexes and lease reservations, and apply the documented clock policy. Durably advance the startup epoch before accepting new operations; recovered old tokens remain invalid even while their reservations wait to expire.
- Reject a second process opening the same data directory for writing. Define shutdown and failed-storage states; do not continue publishing success after a storage failure.
- Connect the simulator to the production queue and storage implementation.
- Track invoked calls, persisted effects, and returned responses separately in the checker. Use an independent history/model oracle that allows uncertain outcomes for interrupted calls; do not reconstruct expected truth by copying the queue's recovered state.

**Acceptance**

- A successful enqueue survives restart; a successful ACK never returns as deliverable work.
- A stale token cannot ACK a new lease, including after restart. Replay preserves ordering and never reuses exposed identities.
- Regression tests cover lease expiry → backward wall jump → crash/reopen, and ready promotion → backward wall jump → crash/reopen. Old tokens never reactivate; ready/delayed recovery follows the documented eligibility policy.
- Crash tests cover before write, during write, before sync, during sync, after sync before response, and after response for each mutation.
- Model/history checks run after each visible operation and recovery; internal fault steps check the relevant disk/state invariants.
- After faults stop, advance time and drain the queue to verify eventual redelivery/progress as well as state safety.

**Milestone:** a usable durable queue with deterministic crash reproduction, before checkpoints or compaction.

## Stage 5 — Add crash-safe checkpoints

**Work**

- Define a versioned checkpoint with checksum, generation, covered WAL sequence, live jobs, prior-grant eligibility, lease state, and identity high-water marks.
- Initially capture a consistent checkpoint at a serialized queue boundary. Defer background snapshot complexity until measurements justify it.
- Specify and implement the publication protocol: write a new generation, sync its contents, publish the recovery pointer/name using the supported filesystem protocol, and persist the required directory metadata.
- Retain enough prior checkpoint/log state to recover while publication is incomplete. Load the selected valid checkpoint and replay only its required log suffix.

**Acceptance**

- Checkpoint-plus-log recovery matches full-log recovery for the same history.
- Crash at every publication boundary, including directory-sync and pointer-update boundaries, always leaves a complete usable recovery path.
- Missing/incomplete new generations are handled without selecting partial state. Fallback is allowed only when its required log history is still available.
- No log deletion occurs in this stage.

## Stage 6 — Add safe compaction and storage reclamation

**Work**

- Add WAL segments and a rotation protocol if not already introduced for checkpointing.
- Reclaim only records covered by a durably published checkpoint and no longer needed by any permitted recovery path.
- Preserve live delayed/ready/leased jobs and identity high-water marks. Define how old ACK history can be discarded while duplicate/stale acknowledgements still cause no new transition.
- Make interrupted cleanup retryable. Bound normal disk growth with explicit checkpoint/compaction triggers.

**Acceptance**

- Compare state before compaction and after reopen; ordering, leases, and ACK behavior remain equivalent.
- Crash before/after rotation, manifest changes, individual deletions, and metadata syncs. No live job disappears and no acknowledged job reappears.
- Repeated recovery and compaction are safe. Tests demonstrate reclaimed bytes/segments on a workload with completed jobs.
- New operations around maintenance boundaries remain represented in the retained recovery state.

## Stage 7 — Complete fault campaigns and bug regression tooling

**Work**

- Expand mixed workloads to multiple workers, duplicate/stale ACKs, delayed jobs, crashes/restarts, torn writes, interrupted/failed syncs, independent clock jumps, checkpointing, and compaction.
- Use named random streams derived from the root seed for workload, scheduling, and faults. Save exact traces to help reproduce older failures when generation logic changes.
- Check completed-enqueue durability, single terminal ACK, valid lease ownership, ordering, recovery equivalence, and eventual progress under a fair fault-free drain phase.
- Maintain short deterministic regressions and targeted boundary schedules alongside randomized campaigns. Test the checker with deliberate local violations so a passing campaign is meaningful.
- Make failure output include the seed, step, invariant, configuration, and replay command. Add every discovered bug to `docs/bugs.md` and the regression corpus.

**Acceptance**

- Same seed/version/configuration reproduces the operation and fault trace, including the failing step when applicable.
- All targeted crash schedules and saved regressions pass.
- `./gradlew simulate --seed 8812934 --steps 100000` and `./gradlew simulate --runs 1000` complete with explicit per-run step counts and aggregate executed steps.
- CI runs bounded tests/seeds; longer campaigns can run separately. A failure produces a nonzero exit status and a retained replay artifact.
- Counts distinguish runs executed from tests merely configured. No bug count is invented if the campaign finds none.

## Stage 8 — Add group commit and finish production integration

**Work**

- Keep sync-per-operation as the baseline; add bounded batching by record count/bytes and maximum wait time.
- Let concurrent callers submit through the existing serialized state-transition path. Preserve operation/response ordering and implement an explicit durable sequence watermark for batch completion.
- Complete each call only after its own required record is durable. Handle batch sync failure, shutdown with pending calls, and recovery after partial batches.
- Harden the real clock/scheduler adapters and exercise the public API with concurrent producers/workers. Use bounded pending work and explicit overload/close behavior.
- Run the same model, recovery, and fault checks against both commit modes, including batch/maintenance interactions.

**Acceptance**

- No caller receives success before its record's durable boundary.
- Tests cover crashes and I/O errors around every batch boundary, including successful sync followed by lost responses.
- Duplicate deliveries can occur under the documented contract; duplicate terminal transitions cannot.
- Real-adapter integration tests cover directory ownership, concurrent submissions, shutdown, reopen, and injected I/O failures.
- All earlier regressions and the Stage 7 campaign gates pass in both modes.

## Stage 9 — Benchmark and publish measured evidence

**Work**

- Implement `./gradlew bench` and document it in `bench/README.md`.
- Measure durable enqueue throughput for sync-per-enqueue and group commit with the same payloads, producer counts, storage, and durability settings. Include latency percentiles and batch configuration so throughput has context.
- Measure recovery time against stated WAL record/byte counts, live-job counts, and checkpoint configurations. Measure end-to-end enqueue/lease/ACK throughput separately from enqueue-only throughput.
- Record hardware, OS/filesystem, JDK, JVM options, heap, warmup, measurement duration, repetitions, and code revision. Retain raw results and distinguish cold/warm-cache conditions when measured.
- Update README results only with actual measurements. Document API examples, failure semantics, supported storage, simulator replay, and how to run tests.

**Acceptance**

- A fresh checkout with the documented prerequisites can run every README command.
- Reported runs/steps, regression bugs, throughput, and recovery figures are traceable to saved outputs.
- Correctness gates pass before benchmark results are used to describe the implementation.
- Unmeasured values stay `TBD`; no performance target is presented as an achieved result.

## Planned layout

```text
build.gradle(.kts), settings.gradle(.kts), gradlew, gradle/wrapper/
src/main/java/.../api/         public queue API and value types
src/main/java/.../core/        state transitions, ready/delayed indexes, leases
src/main/java/.../storage/     WAL, recovery, checkpoints, compaction
src/main/java/.../env/         Clock, Disk, Scheduler and real adapters
src/test/java/.../            contract, component, recovery, integration tests
src/simulation/java/.../      reference model, simulated adapters, seeded runner
src/benchmark/java/.../       benchmark workloads and runner
docs/decisions/               behavioral and storage decision records
docs/bugs.md                 discovered bugs and replay instructions
bench/README.md               measurement setup
bench/results/               retained measurement evidence
```

Separate simulation/benchmark source sets keep their tooling out of the queue's production dependency surface. Package and file names can be finalized in Stage 0.

## Technical references used for the plan

- Java's [`FileChannel.force`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)) documents file durability for local storage and platform-dependent metadata behavior. The storage adapter must separately define checkpoint publication and directory persistence for the supported platform.
- Java's [`System.nanoTime`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html#nanoTime()) measures elapsed time within a JVM and has an arbitrary origin. Persisting its raw values does not create portable deadlines across restarts; this motivates the explicit recovery policy in Stage 0.
