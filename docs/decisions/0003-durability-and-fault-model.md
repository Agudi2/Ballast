# 0003 — Durability boundary and storage fault model

Status: accepted for Stage 0. No durable queue or production storage adapter exists yet, and no crash-safety claim has been validated. Stage 1 supplies only a simulated disk skeleton.

## Supported target

The first durable-adapter target is Linux on a local ext4 filesystem, with an operating system and storage device that honor successful sync requests. Record the tested kernel, JDK, filesystem/mount configuration, and device in later validation evidence. Passing capability checks alone does not prove power-loss durability.

macOS is a development platform for the Java build and non-storage tests at this stage. Other filesystems, network mounts, container storage layers, and macOS durable operation remain unsupported until their protocol and validation are documented. The durable provider must reject unsupported configurations or missing required capabilities instead of silently weakening its guarantees. Stage 0 does not contain such a provider.

## Commit and failure rules

- An enqueue returns its job ID only after the enqueue and required identity state are durable.
- A lease returns its token only after the grant and required identity state are durable.
- An ACK returns `APPLIED` only after the terminal record is durable. A successful ACK must not become deliverable work after recovery.
- A startup epoch is durable before the reopened queue serves calls. Exposed job IDs, epochs, and lease sequences never wrap or get reused.
- A call interrupted before its response has an uncertain outcome: its complete record may survive. Returned success and invocation-without-response must be represented separately by the checker.
- A write/sync error propagates as `IOException`, with no success response for that operation. Since failure may occur after some persistence, it does not mean rollback. The instance enters a failed state and requires close/reopen and recovery before serving more operations.

The initial baseline syncs every durable operation. Group commit may later batch records, but each response must wait until the durable sequence watermark covers that operation's complete record.

## File and metadata protocol

The disk abstraction must distinguish writes from persistence. Baseline file commits use complete write loops followed by a successful `FileChannel.force(true)` on the affected file. Java's [file-channel contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)) describes local-device persistence and platform-dependent metadata behavior; it is not a portable directory-publication protocol.

The Linux adapter must also support directory `fsync` for durable file creation, rename/publication, and deletion where the recovery protocol depends on those changes. Linux documents that syncing a file does not necessarily persist its directory entry; see [`fsync(2)`](https://man7.org/linux/man-pages/man2/fsync.2.html). If Java/runtime facilities cannot provide the required directory operation on the selected platform, the adapter must supply a verified platform mechanism or reject durable open.

Stages 3–6 must specify and test the exact ordering for each operation. At minimum:

1. Persist newly written WAL/checkpoint file contents before relying on them.
2. Persist the directory entry that makes a new file reachable before reporting a success dependent on it.
3. Publish a checkpoint/manifest only after its referenced files are durable; persist the publication's directory metadata.
4. Retain the prior complete recovery path until the new one is durable.
5. Remove obsolete files only when no permitted recovery path still needs them; make interrupted deletion retryable.

Atomic rename alone is not a substitute for the persistence ordering. Initial directory creation, segment rotation, tail repair, checkpoint publication, and cleanup all require explicit crash boundaries. Exclusive ownership and recovery finish before accepting mutations.

## Fault model

| Fault/event | Required simulated behavior |
|---|---|
| Crash before or during write | Lose process state; unsynced bytes/metadata may persist partially or disappear. Permit torn records at byte boundaries. |
| Short or zero-progress write | Return the actual progress; production code must complete the write or report failure without an infinite retry loop. |
| Crash during sync, or sync reports failure | Some permitted writes may already persist, but the call cannot establish a successful durability boundary. |
| Sync returns successfully | Its covered data/metadata persist across later simulated crashes. Subsequent unsynced operations may still be lost. |
| Crash after sync but before response | Recover the committed effect even though the caller never received success. |
| Clock jump or changed worker ordering | Drive these through the injected clock/scheduler and seed, not host timing. |

The README's “lost fsyncs” means interrupted or failed syncs, not a device falsely reporting that data is durable and then losing it. Arbitrary destruction of already-synced data, external modification of queue files, and storage devices that lie about persistence are outside this guarantee.

## Recovery validation

Versioned records have bounded lengths, sequence information, and a CRC over the specified record contents. On a recoverable damaged append tail, stop at the first invalid record and truncate to the last complete valid boundary; do not scan onward and apply later-looking records. Unknown format versions and structurally invalid histories fail explicitly rather than being treated as an empty queue.

CRC detects damage; it cannot restore arbitrary corrupted durable data. Tests of append-tail repair must not be presented as evidence that silent interior corruption can be repaired without losing completed enqueues. Checkpoints must preserve live jobs, prior-grant eligibility, reservation state, and identity high-water marks, and replay only their required suffix.

The checker tracks operation invocations, returned responses, and independently modeled persistence. It must enforce every returned success while allowing uncertain outcomes for interrupted calls. It must not derive its expected state by copying the queue's own replay result. Targeted crash schedules cover every write/sync/publication boundary; seeded campaigns supplement those tests. After faults stop, a drain phase checks eventual redelivery as well as safety.
