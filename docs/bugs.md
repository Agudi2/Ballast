# Ballast bug log

No implementation bugs have been recorded yet. Stages 0–1 establish contracts, the build, and model-only simulation; production queue fault campaigns have not run. An empty log is not evidence that the queue is crash-safe.

Add one entry for each discovered implementation bug, including bugs found before the seeded simulator exists. Keep the original reproducer and identify the regression that prevents recurrence.

## Entry format

```text
ID and short title:
Status: open / fixed
Discovered in stage:
Source revision and simulator version:
Root seed and derived run seed: (or "not applicable" with a deterministic reproducer)
Configuration and step number:
Replay command / exact reproduction steps:
Failed invariant and observed behavior:
Root cause:
Fix:
Regression test or saved trace:
Validation command and result:
```

Count only actual recorded discoveries in project results. Do not count hypothetical examples, deliberately injected checker-test violations, or configured-but-unexecuted simulation runs as discovered bugs.
