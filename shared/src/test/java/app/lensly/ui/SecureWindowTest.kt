package app.lensly.ui

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

// Plain JVM test: no Android or Robolectric types, only strings passed to isSecureRequired.
class SecureWindowTest {

    private val motorolaHardware = "mt6855"
    private val motorolaProduct = "fogos_gp"
    private val motorolaFingerprint = "motorola/fogos_gp/fogos:15/V1VL35.10-4/1:user/release-keys"

    private val ranchuFingerprint = "generic/sdk_gphone64_x86_64/emu64xa:16/BE2A/12:userdebug/test-keys"

    // The release build passes debug = false and benchmark = false (BuildConfig of :shared), so it keeps FLAG_SECURE on an emulator.
    @Test
    fun releaseOnEmulatorIsSecure() {
        assertTrue(isSecureRequired(debug = false, benchmark = false, "ranchu", "sdk_gphone64_x86_64", ranchuFingerprint))
    }

    @Test
    fun releaseOnMotorolaIsSecure() {
        assertTrue(isSecureRequired(debug = false, benchmark = false, motorolaHardware, motorolaProduct, motorolaFingerprint))
    }

    @Test
    fun debugOnMotorolaIsSecure() {
        assertTrue(isSecureRequired(debug = true, benchmark = false, motorolaHardware, motorolaProduct, motorolaFingerprint))
    }

    @Test
    fun benchmarkOnMotorolaIsSecure() {
        assertTrue(isSecureRequired(debug = false, benchmark = true, motorolaHardware, motorolaProduct, motorolaFingerprint))
    }

    @Test
    fun debugOnRanchuEmulatorIsNotSecure() {
        assertFalse(isSecureRequired(debug = true, benchmark = false, "ranchu", "sdk_gphone64_x86_64", ranchuFingerprint))
    }

    @Test
    fun benchmarkOnRanchuEmulatorIsNotSecure() {
        assertFalse(isSecureRequired(debug = false, benchmark = true, "ranchu", "sdk_gphone64_x86_64", ranchuFingerprint))
    }

    @Test
    fun debugOnGoldfishEmulatorIsNotSecure() {
        assertFalse(isSecureRequired(debug = true, benchmark = false, "goldfish", "sdk_phone_x86", "unknown"))
    }

    @Test
    fun debugWithEmulatorProductNameIsNotSecure() {
        assertFalse(isSecureRequired(debug = true, benchmark = false, "x86", "emulator64_x86", "unknown"))
    }

    @Test
    fun debugWithGenericFingerprintIsNotSecure() {
        assertFalse(isSecureRequired(debug = true, benchmark = false, "x86", "x86", "generic/x86/x86:12/SP1A/1:eng/test-keys"))
    }

    @Test
    fun motorolaValuesAreNotDetectedAsEmulator() {
        assertFalse(isEmulator(motorolaHardware, motorolaProduct, motorolaFingerprint))
    }
}
