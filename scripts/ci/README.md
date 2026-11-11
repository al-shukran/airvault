# CI scripts

Thin wrappers over the Gradle commands in `docs/testing/guideline.md`. Workflows call them; you can run the same commands locally.

## PR partitions — `run-partition.sh <partition>`

| Partition | Selector |
|---|---|
| `core-wallet` | `app.lensly.core.wallet.*` |
| `core-ur-evm` | `app.lensly.core.ur.*` and `app.lensly.core.evm.*` |
| `core-parse` | `app.lensly.core.parse.*` |
| `core-vault` | `app.lensly.core.vault.*` |
| `core-registry` | `app.lensly.core.registry.*` and `app.lensly.core.registry.update.*` |
| `ui` | `app.lensly.ui.*` |
| `app` | `app.lensly.app.*` and `app.lensly.SmokeTest` |
| `registry-pack` | `:tools:registry-pack:test` |
| `manifest` | `check-manifest.sh` |

`core-parse`, `app` and `registry-pack` are not in the guideline 2.2 table: tests already live in those packages, so leaving them out would run nothing for changes there.

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

## Nightly fuzz — `fuzz-nightly.sh`

Fuzzes each Jazzer target for 15 minutes, one Gradle invocation per target (`JAZZER_FUZZ=1`, which Jazzer's JUnit integration reads). `--list` prints the targets.

```bash
scripts/ci/fuzz-nightly.sh          # all four targets, about an hour
scripts/ci/fuzz-nightly.sh --list
```

Duration is not a flag: each `@FuzzTest` carries `maxDuration = "15m"`, which Jazzer passes to libFuzzer as `-max_total_time=900`. The JUnit integration has no `-Djazzer.max_total_time` property.

**PR lane.** The same targets run with no environment variable, as ordinary JUnit tests: Jazzer replays the committed seeds. `@FuzzTest` is a JUnit 5 `@ParameterizedTest`, so the unit-test JVM runs both engines — Jupiter for the fuzz targets, Vintage for every existing JUnit 4 test.

**Seeds.** `app/src/test/resources/fuzz-seeds/<target>/` holds valid and malformed inputs, named in that directory's `index.txt`; the tests read them through `@MethodSource`. A crash a nightly run finds lands in `app/src/test/resources/app/lensly/fuzz/<Class>Inputs/<method>/`, which the regression run replays as soon as the file is committed.

