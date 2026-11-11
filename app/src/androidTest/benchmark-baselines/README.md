# Benchmark baselines

These files are the reference-device numbers that `scripts/ci/benchmark-gate.sh` compares a fresh AndroidX
Benchmark run against (docs/testing/guideline.md 1.7). A regression of more than 10% fails the gate.

**There are no numbers here yet.** Nobody has run the microbenchmarks on the reference device, so shipping invented
values would make the gate meaningless. The directory holds this README only until a reference-device run exists.

## Why recording is a manual procedure

AndroidX Benchmark refuses to time interpreted code. A run on a freshly installed APK aborts with
`NOT-AOT-COMPILED` until the app package has been ahead-of-time compiled, which on a device means:

```bash
adb shell cmd package compile -m speed -f app.lensly
```

`./gradlew :app:connectedBenchmarkAndroidTest` cannot do that for you, and a Gradle task cannot do it reliably
either: the task installs both APKs at the start of every run, and installing a package clears its compiled code, so
anything compiled before the install is gone by the time the tests start. AGP has no hook between "installed" and
"instrumentation started". Gradle-driven runs therefore stay useful for compile/lint feedback and for emulator trend
data, and baseline recording is the manual sequence below.

## Recording a baseline

Run on the reference device, plugged in, at room temperature, on an otherwise idle phone, with no charger plugged
into the USB port of the host if the phone charges through it (charging heats the phone and skews timings).

The microbenchmarks live in `app/src/androidTest` (`app.lensly.benchmark.*`). They need two APKs: the app's
`benchmark` variant, and the instrumentation APK. `androidx.benchmark.junit4` is on the benchmark variant's runtime on
purpose, because `AndroidBenchmarkRunner` launches `androidx.benchmark.IsolationActivity` inside the app under test:
without it on the app side the manifest merge misses the activity and every run dies with "Unable to resolve activity
for androidx.benchmark.IsolationActivity".

```bash
# 1. Build both APKs (no device needed).
./gradlew :app:assembleBenchmark :app:assembleBenchmarkAndroidTest

# 2. Install the app first, then the test APK, onto the reference device.
adb install -r -t app/build/outputs/apk/benchmark/app-benchmark.apk
adb install -r -t app/build/outputs/apk/androidTest/benchmark/app-benchmark-androidTest.apk

# 3. AOT-compile the app in speed mode. This is the step a Gradle run cannot keep, because a Gradle run reinstalls.
adb shell cmd package compile -m speed -f app.lensly

# 4. Run only the benchmark classes, so the a11y, e2e and vault tests stay out of the run.
adb shell am instrument -w \
    -e class 'app.lensly.benchmark.*' \
    app.lensly.test/androidx.benchmark.junit4.AndroidBenchmarkRunner

# 5. Pull the result JSON. `am instrument` writes it to the app's external media directory, and the file name is
#    timestamped, so take the newest one.
adb shell ls -t /sdcard/Android/media/app.lensly/ | head -1
adb pull /sdcard/Android/media/app.lensly/<newest>-benchmarkData.json /tmp/benchmarkData.json

# 6. Record it.
scripts/ci/benchmark-gate.sh --record /tmp/benchmarkData.json
```

Steps 2 to 4 are what `:app:connectedBenchmarkAndroidTest` does in one pass. The difference is that it reinstalls both
APKs every time, so the compile from step 3 never survives into the test run.

`--record` writes `baselines.json` next to this README and refuses to run with `CI=true`: baselines are recorded by a
person on the reference device, never by a CI job. The recorded `device` and `date` are copied from the result file
and the record time.

## Checking a run (reference device only)

The same manual sequence, then the gate without `--record`:

```bash
scripts/ci/benchmark-gate.sh --reference-device /tmp/benchmarkData.json
```

`./gradlew :app:connectedBenchmarkAndroidTest` still builds, installs and runs the benchmarks, so it is fine for a
smoke check that nothing crashes. Its numbers are not gate-quality on a device that has not been AOT-compiled, and
the gate makes no verdict without `--reference-device` anyway.

The gate fails when the median of any benchmark is more than `BENCHMARK_MAX_REGRESSION_PCT` (default 10) percent
above the baseline. Improvements are reported and do not fail. A benchmark that is in the result file but not in the
baseline fails the gate, so a new benchmark cannot slip in unmeasured.

The gate only judges the reference device. Without `--reference-device` (or `BENCHMARK_REFERENCE_DEVICE=1`) it prints
"no verdict" and exits 0: an emulator number must never pass or fail the 10% gate. On the reference device a missing
`baselines.json` fails closed.

## Emulator numbers

Emulator runs are trend data only. Do not record or compare emulator baselines: use a physical reference device.

## Macrobenchmark (cold start)

Not wired yet. Macrobenchmarks need a separate `com.android.test` module that targets `:app`'s `benchmark` variant,
and the version catalog has no `com.android.test` plugin alias. Adding the module without a reference device would
also ship a lane nobody can run. It is deferred; `benchmark-macro-junit4` is already in the catalog for that module.
