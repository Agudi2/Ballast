# 0001 — Queue API and state machine

Status: accepted for Stage 0. These are implementation contracts; queue operations are not implemented yet.

## Decision

Use the `dev.ballast.api` package with a small synchronous API. A queue owns one data directory, and exactly one process may own that directory for writing. Concurrent callers submit to one serialized state-transition path; the implementation decides the order in which overlapping calls enter that path.

| Operation | Result | Contract |
|---|---|---|
| `QueueFactory.open(Path)` | `JobQueue` | Create/open the directory, acquire exclusive ownership, then recover the queue. Storage validation and the durable startup epoch complete before returning. The durable provider arrives in Stage 4. |
| `enqueue(EnqueueRequest)` | `JobId` | Create one job; return only after its enqueue record is durable. |
| `lease(Duration)` | `Optional<Lease>` | Grant the next eligible job and persist the grant before returning it; return empty when none is eligible. It does not wait for work to arrive, but can block on serialization and storage. |
| `acknowledge(JobId, LeaseToken)` | `AckResult` | Return `APPLIED` only for a newly durable terminal transition; otherwise return `NOT_APPLIED` without changing the job. |
| `close()` | none | Stop accepting work, resolve admitted operations, release resources/ownership, and report I/O errors. Successful close is repeatable. |

Each operation may throw `IOException` for I/O failure; open also reports ownership, unsupported-storage, recovery, and initialization failure this way. An error from a mutation does not establish that the mutation had no effect. After a storage failure, the instance stops serving operations until close/reopen and recovery; it must not report further successful mutations. Validation failure occurs before any mutation. Null arguments are rejected with `NullPointerException`; invalid values use `IllegalArgumentException`. Operations after close or an operational I/O failure throw `IllegalStateException`; `close()` remains allowed and is idempotent.

`QueueFactory` is a provider interface, not a static factory that pretends to open a working queue. Stage 0 supplies API/value types only.

## Values and limits

- `JobId` contains a queue UUID and a positive sequence number. The UUID is persisted with the queue; a new directory has a distinct identity.
- `LeaseToken` contains the queue UUID, positive startup epoch, and positive lease sequence. Job IDs and exposed lease tokens must never be reused after restart or compaction. Counter exhaustion fails explicitly instead of wrapping.
- `EnqueueRequest` contains payload bytes, an integer priority, and an absolute `Instant readyAt`. Payload length is 0 through 1,048,576 bytes inclusive. Constructors and accessors copy payloads defensively.
- Every integer priority is valid; smaller values win. `readyAt` must be exactly representable as epoch milliseconds, without silently truncating fractional milliseconds. Negative epoch-millisecond timestamps are valid.
- Visibility timeouts are whole milliseconds, strictly positive, and at most seven days. Validate before selecting a job, including when the queue is empty.
- `Lease` contains the job ID, token, copied payload, original priority, original `readyAt`, and original visibility timeout. Its job ID and token identify the same queue. The timeout describes the grant, not a fresh countdown starting when the caller reads the result.

Queue IDs distinguish namespaces, not authorization domains. Lease tokens prevent accidental or stale acknowledgement; this local API does not provide authentication or a security boundary against a caller that can forge values.

## State and ordering

```text
enqueue ── future readyAt ──> DELAYED ── eligible ──> READY
        └─ already eligible ──────────────────────> READY
READY ── lease grant ──> LEASED ── valid ACK ──> ACKNOWLEDGED
                         └─ timeout ──> READY
```

Only eligible jobs compete in the ready heap. Choose the smallest `(priority, readyAt, enqueueSequence)` tuple; re-leasing preserves the job's original tuple. Delayed jobs need a separate readiness index so that a high-priority future job cannot hide eligible work. Expiry returns a job to ready without assigning a new job ID.

An ACK is valid only if the job is currently leased, the supplied token is its current token, the token belongs to the current startup epoch, and the lease has not expired at the ACK's serialized transition point. An admitted valid ACK is not invalidated retroactively because its durable sync finishes after the deadline. Timer callbacks and calls share serialized state ownership; expired tokens are rejected even if their timer callback has not run.

`ACKNOWLEDGED` is terminal. At most one ACK transition can apply to a job, but the API does not retain unbounded tombstones merely to distinguish repeated ACKs from unknown jobs. Duplicate, unknown, wrong-job, foreign-queue, expired, and superseded token attempts all return `NOT_APPLIED`. Null or structurally invalid values still fail validation.

## Contract examples

| Sequence | Required result |
|---|---|
| Enqueue ready jobs A and B with equal priority/time | The job with the earlier enqueue sequence leases first. |
| High-priority A is scheduled for tomorrow; lower-priority B is ready | B can lease now. |
| Lease A with token T; acknowledge A/T twice | First successful call returns `APPLIED`; second returns `NOT_APPLIED`. Only one terminal transition occurs. |
| A/T expires; A is leased again as U; acknowledge A/T | `NOT_APPLIED`; U remains the current lease. |
| A/T is live; queue restarts; acknowledge A/T | `NOT_APPLIED`; all pre-restart tokens are invalid. See the [time policy](0002-time-and-restart.md). |
| Enqueue becomes durable; process crashes before returning its ID | Recovery may contain that job. Retrying enqueue creates another job; enqueue deduplication is outside this API. |
| ACK becomes durable; process crashes before returning `APPLIED` | Recovery must not deliver the job again. A retry may return `NOT_APPLIED`. |

## Consequences

Delivery is at least once, not exactly-once execution of external worker effects. A worker can complete an external action, lose its lease before acknowledging, and cause that action to be repeated on redelivery. Progress assumes continued polling, advancing time, eventual storage health, and a turn under the scheduling policy. Strict priority can starve lower-priority jobs.

Networking, replication, clustering, worker hosting, cancellation, lease extension, and enqueue retry deduplication are outside the initial API. API behavior beyond value validation is verified when the queue/model stages implement it; these examples are not claims of passing queue tests.
