# Stage 1 verification

Status: complete. Verified on 2026-10-27 with Temurin 21.0.13 and Gradle 9.2.1 on macOS arm64.

## Delivered

- `Clock`, `Disk`, and `Scheduler` environment interfaces; real clock and scheduler adapters.
- Virtual time and deterministic callbacks with equal-deadline ordering, cancellation, and surfaced callback failures.
- A simulated disk skeleton that distinguishes volatile/durable bytes and directory names.
- An independent scan-based reference model, including lease expiry and idealized restart semantics.
- A seeded `simulate` command, bounded model workload, and per-run replay traces with source/runtime/configuration metadata.

Production queue behavior, WAL storage, real disk durability, torn writes, and the production comparison/fault checker remain future stages.

## Checks

The environment/model tests first failed because their required implementations were absent. The separate-process replay tests then failed with the missing runner. After implementation, the final Java 21 suite passes all 26 tests with zero failures, errors, or skips.

| Check | Result |
|---|---|
| API values and Java 21 runtime | 8 tests pass |
| Real clock/scheduler | 2 tests pass |
| Virtual time, deterministic scheduling, simulated disk | 6 tests pass |
| Reference model ordering, expiry, ACKs, and recovery | 6 tests pass |
| CLI replay, changed seeds, invalid options, output failure | 4 tests pass |
| Build and Javadocs | Pass without warnings |
| Root seed 8812934, 100,000 steps | 1 model-only run, 100,000 steps completed |
| Default root seed 1, 1,000 runs | 1,000 model-only runs, 1,000 steps each; 1,000,000 total steps completed |

The saved acceptance suite therefore contains 1,001 runs and 1,100,000 steps. These counts describe the saved suite, not cumulative invocations or production fault coverage. Unit-test subprocess runs and replaced earlier verification traces are not added to these figures.

## Reproduction

With a Java 21 JDK selected, run from the repository root:

```bash
./gradlew test build
./gradlew simulate --seed 8812934 --steps 100000 --trace-dir build/simulation/acceptance-seed
./gradlew simulate --runs 1000 --trace-dir build/simulation/acceptance-runs
```

For this checkout's ignored local tools, prefix commands with:

```bash
env JAVA_HOME="$PWD/.tools/jdk-21" GRADLE_USER_HOME="$PWD/.gradle-user-home"
```

The actual verification also used `--offline --no-daemon` after dependencies were cached. Reports and evidence are local generated files:

- `build/reports/tests/test/index.html` and `build/test-results/test/TEST-*.xml`.
- `build/simulation/acceptance-seed/run-0.trace`.
- `build/simulation/acceptance-runs/run-0.trace` through `run-999.trace`.
- `build/stage1-campaign.log`, containing each completed run's trace SHA-256 and the aggregate summary.

The accepted source/build fingerprint is `b80ccdd1b96b6fd59097d72d3501f0802fd11f6d16b05b2df5f9628ee7380eec`. The seed-8812934 trace SHA-256 is `9c4038ac12e35862a49d752cc8092cdb6dd6f9f5a9c7a1b2c6bf2034bf1948c2`. Work is uncommitted; the fingerprint, rather than the preceding Git commit alone, identifies the tested sources.

Generated evidence is ignored by Git and can be recreated. Source changes or different runtime metadata intentionally change fingerprints and full-trace hashes; same-input replay checks compare fresh processes on the same source/runtime.
