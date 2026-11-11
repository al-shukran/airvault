# Benchmark baselines

These files are the reference-device numbers that `scripts/ci/benchmark-gate.sh` compares a fresh AndroidX
Benchmark run against (docs/testing/guideline.md 1.7). A regression of more than 10% fails the gate.

**There are no numbers here yet.** Nobody has run the microbenchmarks on the reference device, so shipping invented
values would make the gate meaningless. The directory holds this README only until a reference-device run exists.

## Recording a baseline

Run on the reference device, plugged in, at room temperature, on an otherwise idle phone, with no charger plugged
into the USB port of the host if the phone charges through it (charging heats the phone and skews timings).

```bash
./gradlew :app:connectedBenchmarkAndroidTest
# AGP writes the result JSON under app/build/outputs/; pick the newest one.
scripts/ci/benchmark-gate.sh --record app/build/outputs/androidTest-results/benchmark/<run>/benchmarkData.json
```

`--record` writes `baselines.json` next to this README and refuses to run with `CI=true`: baselines are recorded by a
person on the reference device, never by a CI job. The recorded `device` and `date` are copied from the result file
and the record time.

## Checking a run

```bash
./gradlew :app:connectedBenchmarkAndroidTest
scripts/ci/benchmark-gate.sh app/build/outputs/androidTest-results/benchmark/<run>/benchmarkData.json
```

The gate fails when the median of any benchmark is more than `BENCHMARK_MAX_REGRESSION_PCT` (default 10) percent
above the baseline. Improvements are reported and do not fail. A benchmark that is in the result file but not in the
baseline fails the gate, so a new benchmark cannot slip in unmeasured.

When `baselines.json` is missing the gate fails closed only under `CI=true`; locally it prints how to record one and
exits 0.

## Emulator numbers

Emulator runs are trend data only. Do not record or compare emulator baselines: use a physical reference device.

## Macrobenchmark (cold start)

Not wired yet. Macrobenchmarks need a separate `com.android.test` module that targets `:app`'s `benchmark` variant,
and the version catalog has no `com.android.test` plugin alias. Adding the module without a reference device would
also ship a lane nobody can run. It is deferred; `benchmark-macro-junit4` is already in the catalog for that module.
