# Testing docs

Start with [guideline.md](guideline.md): test layers, CI design, conventions. Its section 5 lists where the shipped build differs from the spec text.

## Related docs

- [accessibility.md](accessibility.md): the accessibility decision and what gates a release.
- [release-checklist.md](release-checklist.md): manual device steps before shipping.

## Local commands

Run from the repo root. These wrap the Gradle commands in the guideline.

| Command | What it runs |
|---|---|
| `scripts/ci/run-partition.sh <partition>` | One PR partition. Names: `core-wallet`, `core-ur-evm`, `core-parse`, `core-vault`, `core-registry`, `ui`, `app`, `registry-pack`, `manifest`. |
| `scripts/ci/run-extended.sh <kind>` | One nightly layer. Kinds: `instrumented`, `e2e`, `screenshot-testing`, `perf`, `security`, `fuzz`. |
| `scripts/ci/fuzz-nightly.sh` | Jazzer targets, 15 min each. Slow: CI only. |
| `scripts/ci/benchmark-gate.sh <result.json>` | Compares a benchmark result against the reference-device baseline; fails past 10% regression. `--record` rewrites the baseline. |

## Notes

- Add `-PprLane` to a local Gradle run to match the PR lane for `ui` (skips pixel tests).
- `scripts/ci/run-extended.sh` exits 0 for layers that are not configured yet; set `CI=true` to make that a failure.
