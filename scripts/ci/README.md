# CI scripts

Thin wrappers over the Gradle commands in `docs/testing/guideline.md`. Workflows call them; you can run the same commands locally.

## PR partitions — `run-partition.sh <partition>`

| Partition | Selector |
|---|---|
| `core-wallet` | `app.lensly.core.wallet.*` |
| `core-ur-evm` | `app.lensly.core.ur.*` and `app.lensly.core.evm.*` |
| `core-vault` | `app.lensly.core.vault.*` |
| `core-registry` | `app.lensly.core.registry.*` and `app.lensly.core.registry.update.*` |
| `ui` | `app.lensly.ui.*` |
| `manifest` | `check-manifest.sh` |

Each partition is one `./gradlew :app:testDebugUnitTest --tests ...` call; repeated `--tests` filters are ORed. Gradle's `*` matches across dots, so `registry.*` already reaches `registry.update`; the explicit second filter is belt-and-braces. Unknown names print usage and exit 2.

```bash
scripts/ci/run-partition.sh core-wallet
./gradlew :app:testDebugUnitTest --tests 'app.lensly.core.wallet.*'   # same thing
```

## Manifest guard — `check-manifest.sh`

Fails if the merged debug manifest declares `INTERNET` or `ACCESS_NETWORK_STATE`, or if `android:allowBackup` is not `"false"`. It reuses the intermediate at `app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml` and runs `:app:processDebugMainManifest` only when that file is missing.

## Extended kinds — `run-extended.sh <kind>`

`instrumented`, `e2e`, `screenshot-testing`, `perf`, `security`, `fuzz`. Layers that have not landed yet print "not configured yet" and exit 0 locally; set `CI=true` to make that case fail.

```bash
scripts/ci/run-extended.sh security   # lintDebug, then check-manifest.sh
CI=true scripts/ci/run-extended.sh e2e
```
