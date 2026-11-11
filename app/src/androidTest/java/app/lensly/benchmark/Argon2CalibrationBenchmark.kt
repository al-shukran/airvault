package app.lensly.benchmark

import android.util.Log
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lensly.core.vault.Argon2Kdf
import app.lensly.core.vault.KdfCalibration
import app.lensly.core.vault.KdfParams
import java.util.Arrays
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Argon2 calibration timing (guideline 1.7). One benchmark iteration is one full [KdfCalibration.calibrate] call on
 * the real [Argon2Kdf], warm-up run included, which is what the app does when it creates a vault.
 *
 * The target is about 1 s per derivation (guideline accepts 0.8 to 1.2 s). The numbers depend on the device, so this
 * reports them and asserts nothing about their size: read the `LenslyCalibration` log line, and compare the
 * benchmark timing against the reference device baseline through scripts/ci/benchmark-gate.sh.
 *
 * Run with `./gradlew :app:connectedBenchmarkAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class Argon2CalibrationBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    @Test
    fun calibrateToAboutOneSecond() {
        val password = "q7Vn!2Lx#Rb9Tm4Wc8Zp".toCharArray()
        val salt = ByteArray(16) { (it * 5 + 3).toByte() }
        var params: KdfParams? = null
        var lastDerivationMs = 0L

        try {
            benchmarkRule.measureRepeated {
                var measuredMs = 0L
                params = KdfCalibration.calibrate { candidate ->
                    measuredMs = deriveMs(password, salt, candidate)
                    measuredMs
                }
                lastDerivationMs = measuredMs
            }
        } finally {
            Arrays.fill(password, '\u0000')
        }

        val calibrated = params
        Log.i(
            TAG,
            "calibrated memoryKiB=${calibrated?.memoryKiB} iterations=${calibrated?.iterations} " +
                "lastDerivationMs=$lastDerivationMs targetMs=${KdfCalibration.TARGET_MS}",
        )
    }

    private fun deriveMs(password: CharArray, salt: ByteArray, params: KdfParams): Long {
        val started = System.nanoTime()
        val out = Argon2Kdf.derive(password, salt, params)
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        Arrays.fill(out, 0)
        return elapsed
    }

    private companion object {
        const val TAG = "LenslyCalibration"
    }
}
