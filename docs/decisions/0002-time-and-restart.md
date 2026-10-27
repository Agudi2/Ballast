# 0002 — Time, lease expiry, and restart

Status: accepted for Stage 0. Stage 1 implements clock adapters and tests this policy in the reference model. Production recovery remains Stage 4 work.

## Decision

The injected clock exposes wall time and elapsed time separately. All stored absolute times use exact epoch milliseconds. All configured visibility durations use positive whole milliseconds up to seven days. Arithmetic must be checked or expressed without overflow; an unrepresentable deadline fails before a grant is published.

Use wall time for `readyAt` eligibility. In a running instance, promote a delayed job once wall time reaches `readyAt`; a later backward jump does not demote an already-ready job. Promotion alone is an in-memory latch. Recovery recalculates eligibility from persisted `readyAt` for jobs that have never been durably leased, so such a job can be delayed again after restart if wall time is now earlier than its timestamp.

A durable lease grant proves that the job already became eligible. Preserve that eligibility through later recovery and checkpoints. Its recovered reservation returns directly to ready when its remaining wait expires, even if wall time is now before `readyAt`; it does not re-enter the delayed index. Checkpoints must retain this prior-grant eligibility information even when the current lease has expired.

Use elapsed time for active lease expiry. Take wall and elapsed samples at the serialized lease-grant transition and record the chosen wall expiry and original timeout in the grant. Start the elapsed countdown at that grant transition; append/sync latency consumes part of the timeout. Returning a lease does not promise that the entire timeout remains. A timeout is reached when elapsed time since the grant is greater than or equal to its duration. ACK validation and leasing process due expiries even if timer delivery is late.

Wall-clock jumps do not change an active process's elapsed lease countdown. Do not persist raw elapsed-clock readings for use in another process. Java's [`System.nanoTime()`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html#nanoTime()) has a JVM-local arbitrary origin and supports elapsed differences rather than portable absolute timestamps.

## Recovery policy

Before serving any call after open, durably advance the startup epoch. Every token from an earlier epoch is invalid, whether its former reservation has expired or is still waiting. A crash while advancing the epoch cannot expose tokens from an epoch that was never made durable.

For each recovered, unacknowledged lease reservation, compute:

```text
remaining = clamp(persistedWallExpiry - currentWallTime, 0, originalTimeout)
```

Perform the subtraction safely, including extreme valid timestamps. A zero remaining duration makes the job eligible immediately. Otherwise, keep it reserved for `remaining` elapsed milliseconds after recovery, then return it to ready. The previous worker cannot ACK using the old token during this wait. A subsequent lease uses the new startup epoch and a new lease sequence.

A forward wall jump across restart can shorten or eliminate this reservation. A backward wall jump can extend it to at most one original timeout after that restart. Repeated restarts can impose repeated waits; progress requires an eventual fault-free interval. This is a bounded recovery policy, not a claim that exact real elapsed time can be reconstructed across arbitrary wall-clock changes and process loss.

## Contract examples

Times below are illustrative milliseconds in the injected clock, not measurements from the host.

| Scenario | Required result |
|---|---|
| Grant at elapsed 200 with timeout 100; ACK enters the transition at 299 | ACK may apply if its token is current; sync completion after 300 does not retroactively invalidate it. |
| Same grant; ACK enters at elapsed 300 | Token is expired; return `NOT_APPLIED`, even if its timer callback is pending. |
| Live lease; wall time jumps forward or backward while elapsed time advances normally | Its elapsed expiry is unchanged. |
| Delayed job with `readyAt=500`; wall time advances from 400 to 600 | Promote it to ready. A later jump back to 400 does not demote it in that instance. |
| Same promotion without a durable lease grant, then wall time moves to 400 and the process restarts | Recompute from `readyAt=500`; the job waits until wall time reaches 500 again. |
| Persisted lease expiry 1,100, original timeout 100; restart at wall time 1,050 | Old token is invalid; retain the reservation for 50 elapsed milliseconds, then allow a new lease. |
| Same persisted lease; restart at wall time 900 | Old token is invalid; retain the reservation for 100 elapsed milliseconds, not 200. |
| Same persisted lease; restart at wall time 1,200 | Old token is invalid; its reservation has zero remaining duration. |
| Job with `readyAt=1,000` is durably leased at wall time 1,000 for 100 ms; restart at wall time 800 | Retain its reservation for 100 elapsed ms. On expiry it becomes ready even if wall time is only 900, because its durable grant preserves prior eligibility. |
| Lease expires by elapsed time; wall time jumps backward; process crashes and reopens | The old token never becomes valid again. Replay may recover a reservation, but only the clamped wait and a fresh token can follow. |

## Consequences and tests

The simulator must advance wall and elapsed clocks independently, serialize equal-time events with an explicit tie-breaker, and reproduce restarts with a fresh elapsed origin. The reference model must implement the recovery policy independently of the production timing wheel.

Boundary tests must include exact expiry, delayed timer delivery, a backward jump after ready promotion, the expired-token restart scenario above, large time advances, and overflow at timestamp limits. The production wheel must not expire early due to bucket rounding. A caller may receive an already-expired lease after a sufficiently slow storage operation; its eventual ACK is rejected unless the valid transition already occurred before expiry. These outcomes preserve the contract without pretending storage latency is zero.
