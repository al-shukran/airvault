package app.lensly.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start of the wallet (guideline 1.7). Each iteration force-stops the app, launches `app.lensly.MainActivity`
 * from the launcher and measures time to first frame through [StartupTimingMetric].
 *
 * `CompilationMode.None` keeps the number comparable run to run: no dexopt or baseline-profile step happens between
 * iterations, so every run measures the same bytecode. Move to `Partial` once the app ships a baseline profile and the
 * reference-device baseline was recorded for that mode; changing it invalidates the baseline.
 *
 * Needs a device or emulator. Numbers from an emulator are trend data only; only a physical reference device records
 * a baseline (see app/src/androidTest/benchmark-baselines/README.md and scripts/ci/benchmark-gate.sh).
 *
 * Run with `./gradlew :benchmark:connectedBenchmarkAndroidTest`.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() {
        benchmarkRule.measureRepeated(
            packageName = "app.lensly",
            metrics = listOf(StartupTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.COLD,
            compilationMode = CompilationMode.None(),
        ) {
            startActivityAndWait()
            // startActivityAndWait returns once the activity is resumed; wait for its window so the measurement does
            // not race the first frame on a slow device.
            device.wait(Until.hasObject(By.pkg("app.lensly").depth(0)), 5_000)
        }
    }
}
