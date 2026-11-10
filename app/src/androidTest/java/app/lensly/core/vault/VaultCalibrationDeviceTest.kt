package app.lensly.core.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Arrays
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the production calibration on the real Argon2 (no injected parameters) and logs the result under the tag
 * `LenslyCalibration`, so each device's numbers can be read with `adb logcat -s LenslyCalibration`.
 */
@RunWith(AndroidJUnit4::class)
class VaultCalibrationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun calibratesArgon2ToAboutOneSecondOnThisDevice() {
        runBlocking {
            val vault = Vault(context, RealClock)
            vault.wipe()
            try {
                assertEquals(CreateResult.Created, vault.create("q7Vn!2Lx#Rb9Tm4Wc8Zp".toCharArray()))
                val header = VaultHeader.decode(File(context.noBackupFilesDir, "vault/header.bin").readBytes())
                val kdf = header.kdf
                Log.i(TAG, "calibrated memoryKiB=${kdf.memoryKiB} iterations=${kdf.iterations} parallelism=${kdf.parallelism}")

                val timings = List(3) { measureMs(kdf) }
                Log.i(TAG, "measured derivation ms at these parameters: ${timings.joinToString()}")

                val security = vault.deviceSecurity()
                Log.i(
                    TAG,
                    "strongBox=${security.strongBox} hardwareBacked=${security.hardwareBacked} " +
                        "keySecurityLevel=${header.keySecurityLevel}",
                )

                assertTrue(kdf.memoryKiB == 64 * 1024 || kdf.memoryKiB == 32 * 1024)
                assertTrue(kdf.iterations in 1..KdfCalibration.MAX_ITERATIONS)
                assertTrue("one derivation should take a noticeable time", timings.max() >= 500L)
            } finally {
                vault.wipe()
            }
        }
    }

    private fun measureMs(kdf: KdfParams): Long {
        val password = "q7Vn!2Lx#Rb9Tm4Wc8Zp".toCharArray()
        val salt = ByteArray(VaultHeader.SALT_BYTES) { (it * 5 + 3).toByte() }
        val started = System.nanoTime()
        val out = Argon2Kdf.derive(password, salt, kdf)
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        Arrays.fill(out, 0)
        return elapsed
    }

    private companion object {
        const val TAG = "LenslyCalibration"
    }
}
