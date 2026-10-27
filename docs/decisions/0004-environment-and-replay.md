# 0004 — Environment boundaries and deterministic replay

Status: accepted for Stage 1. The runner exercises the reference model only.

## Environment interfaces

Production code receives `Clock`, `Disk`, and `Scheduler` through `dev.ballast.env`.

- `Clock` exposes signed wall milliseconds and elapsed nanoseconds separately. Elapsed readings have a process-local origin; use differences, not persisted raw values.
- `Scheduler` serializes callbacks. Equal deadlines use registration order; callbacks never run early but can run late. Cancellation succeeds only for pending work, never interrupts running work, and succeeds once. Close cancels pending callbacks and rejects new submissions; it does not wait for a running callback. Queue-level shutdown coordination comes later.
- `Disk` binds one directory and uses flat ASCII names matching `[A-Za-z0-9][A-Za-z0-9._-]*`. Reads/writes use explicit offsets and advance buffer positions by actual progress. File sync covers bytes and length; directory sync covers the namespace. Move atomically replaces a name in the volatile namespace, requiring directory sync for persistence. Close does not imply sync. It is a single-owner interface.

`SystemClock` is the real clock adapter. `RealScheduler` uses one worker and reports callback failures to its owner's supplied error handler. Java's [scheduled executor](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ScheduledThreadPoolExecutor.html) provides delay ordering; the adapter adds explicit cancellation ownership so a running callback cannot be reported as successfully cancelled. A real disk adapter is Stage 3 work, not part of Stage 1.

`VirtualClock` supports advancing both clocks, advancing elapsed time independently, and jumping wall time independently. It carries fractional wall milliseconds when advancing by nanoseconds and rejects overflow without partial updates. `DeterministicScheduler` orders events by elapsed deadline and registration sequence. Tests advance it directly without sleeps; callback failures propagate to the driver, and failed callbacks are not rerun. Callbacks may schedule other callbacks but cannot recursively drive time.

## Simulated disk boundary

`MemoryDisk` keeps current bytes, durable bytes, current names, and durable names separately. The durable namespace retains inode identity through unsynced rename/replacement/deletion, so a crash can restore the old complete file. A Stage 1 crash discards all unsynced changes and reopens the directory.

This is one permitted persistence outcome, not the full fault model. It does not yet inject torn writes, partial persistence, sync failures, or crashes within an operation. Those arrive in Stage 3. Its byte-array file representation is deliberately bounded by Java array sizes and is not a production storage design. The simulation command does not yet connect this disk to a queue or WAL.

## Reference model

`ReferenceQueue` uses a map, linear scans, and independent arithmetic. It shares the public immutable API values but no production queue algorithm. It models idealized completed enqueue, lease, and ACK operations; each modeled mutation immediately becomes part of its conceptual persisted history.

Snapshots preserve queue identity, sequence high-water marks, original jobs, and prior grants. Recovery creates a new startup epoch, rejects old tokens, applies the documented reservation clamp, and distinguishes never-leased jobs from jobs with durable prior eligibility. The clamp uses arbitrary-precision subtraction to avoid copying a future production implementation's overflow logic.

Clock-changing test drivers must call `refresh()` after each explicit time event so promotions and expiries are observed before another clock jump. The runner does this after every generated operation and scheduler advance. In-flight uncertain operations, failed persistence, and comparison against a production queue remain later-stage work.

## Seed and trace format

The runner version is `ballast-model-v1`. A fixed mixing function derives each run seed from the root seed and run index, then separate workload and scheduler streams. Four workers submit a batch of callbacks with seeded delays; equal deadlines retain registration order. All queue choices and canonical-state serialization have explicit iteration order.

Each trace records:

- Runner version, `MODEL_ONLY` mode, Git revision when available, Java version, and a SHA-256 fingerprint of source/build inputs, including uncommitted files under `src`.
- Root seed, run index, derived run seed, workload/scheduler seeds, step count, worker count, live-job limit, and token-history limit.
- Every executed operation in execution order, worker, wall/elapsed time, parameters/result, live count, and a diagnostic state CRC.
- Completion count, or the failing step and failure type. Successful runs print a SHA-256 hash of the entire trace.

The state CRC is a diagnostic checksum, not a correctness oracle. Stage 1 checks model invariants; it cannot establish production queue correctness. A seed reproduces a trace only with the same source/build fingerprint, runner version, runtime, and configuration. The source hash supplements the Git revision because current work can be uncommitted.

`--steps` and `--runs` require positive integers; `--seed` accepts a signed 64-bit integer. Defaults are seed 1, 1,000 steps, and one run. Invalid options produce usage text and exit 2. A run or output failure exits 1, prints seed context and a replay command, and retains whatever trace could be written. Only completed operations count toward the summary.

Traces are written to `build/simulation/run-N.trace`, or a selected `--trace-dir`. Reusing a directory replaces matching trace files; choose a new directory to retain separate campaigns. The runner caps live jobs at 128 and retains at most 128 tokens per worker so long runs have bounded model state. The Gradle runner uses a 256 MiB heap. These are test-workload settings, not queue capacity or performance claims.
