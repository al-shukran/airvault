package app.lensly.core.wallet

import kotlin.math.log2
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import org.junit.Test

class EntropyMixerTest {

    private val zeroDevice = ByteArray(32)

    @Test
    fun commitmentIsSha256OfDeviceEntropy() {
        // Independent check: sha256(32 zero bytes).
        assertEquals(
            "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925",
            EntropyMixer.commitment(zeroDevice),
        )
    }

    @Test
    fun mixIsSha256OfDeviceThenUtf8Input() {
        // Independent check: sha256(32 zero bytes || "123456") and sha256(32 zero bytes || "HTTH").
        assertEquals(
            "16ba88244e0230b0fc84868b703a0e32c344be1b0284f2e67e59715f123748d6",
            EntropyMixer.mix(zeroDevice, "123456").toHex(),
        )
        assertEquals(
            "9c864e076b814afd7316cad069447a5563db4ccffb295cb842d1e9ed5ecd45de",
            EntropyMixer.mix(zeroDevice, "HTTH").toHex(),
        )
    }

    @Test
    fun differentRollsGiveDifferentEntropy() {
        assertNotEquals(
            EntropyMixer.mix(zeroDevice, "123456").toHex(),
            EntropyMixer.mix(zeroDevice, "654321").toHex(),
        )
    }

    @Test
    fun userBitsEstimatesDiceAndCoin() {
        assertEquals(4 * log2(6.0), EntropyMixer.userBits("1234"), 1e-9)
        assertEquals(4.0, EntropyMixer.userBits("HTTH"), 0.0)
    }

    @Test
    fun rejectsMalformedInput() {
        for (bad in listOf("", "0", "7", "h", "HT1", "1 2", "H T")) {
            assertFailsWith<IllegalArgumentException> { EntropyMixer.mix(zeroDevice, bad) }
            assertFailsWith<IllegalArgumentException> { EntropyMixer.userBits(bad) }
        }
    }

    @Test
    fun rejectsWrongDeviceEntropyLength() {
        assertFailsWith<IllegalArgumentException> { EntropyMixer.mix(ByteArray(31), "1") }
    }
}
