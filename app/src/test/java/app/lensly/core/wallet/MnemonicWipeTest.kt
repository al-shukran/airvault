package app.lensly.core.wallet

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** A [Mnemonic] must own only wipeable buffers, and a wiped one must refuse to be read. */
class MnemonicWipeTest {

    // BIP39 vector for 256 zero bits of entropy.
    private val artWords = List(23) { "abandon" } + "art"

    /** Every array field the instance owns, by reflection, so the check cannot miss a new buffer. */
    private fun buffersOf(mnemonic: Mnemonic): List<Any> =
        Mnemonic::class.java.declaredFields.mapNotNull { field ->
            field.isAccessible = true
            val value: Any? = field.get(mnemonic)
            when (value) {
                is ByteArray -> value
                is CharArray -> value
                else -> null
            }
        }

    /** The seed buffer itself, so the test can tell it apart from the words and entropy. */
    private fun seedOf(mnemonic: Mnemonic): ByteArray =
        Mnemonic::class.java.getDeclaredField("seed").run {
            isAccessible = true
            get(mnemonic) as ByteArray
        }

    private fun allZero(buffer: Any): Boolean = when (buffer) {
        is ByteArray -> buffer.all { it == 0.toByte() }
        is CharArray -> buffer.all { it == '\u0000' }
        else -> false
    }

    @Test
    fun wipeZeroesEveryOwnedBuffer() {
        val mnemonic = Mnemonic.fromEntropy(ByteArray(Mnemonic.ENTROPY_BYTES) { (it * 7 + 3).toByte() })
        val buffers = buffersOf(mnemonic)
        val seedBuffer = seedOf(mnemonic)
        assertEquals(3, buffers.size, "the words, entropy and seed buffers")
        assertFalse(buffers.all(::allZero), "buffers hold data before the wipe")
        assertTrue(seedBuffer.all { it == 0.toByte() }, "the seed is empty before it is derived")
        val seed = mnemonic.deriveSeed("TREZOR")
        try {
            assertFalse(seed.all { it == 0.toByte() }, "the derived seed is non-zero")
            assertFalse(seedBuffer.all { it == 0.toByte() }, "the owned seed buffer holds the derived seed")
        } finally {
            seed.fill(0)
        }
        mnemonic.wipe()
        assertTrue(buffers.all(::allZero), "every buffer is zeroed by the wipe")
        assertTrue(seedBuffer.all { it == 0.toByte() }, "the derived seed is zeroed by the wipe")
    }

    @Test
    fun readingAWipedMnemonicThrows() {
        val mnemonic = Mnemonic.fromEntropy(ByteArray(Mnemonic.ENTROPY_BYTES))
        mnemonic.wipe()
        assertFailsWith<IllegalStateException> { mnemonic.size }
        assertFailsWith<IllegalStateException> { mnemonic[0] }
        assertFailsWith<IllegalStateException> { mnemonic.text() }
        assertFailsWith<IllegalStateException> { mnemonic.deriveSeed("TREZOR") }
    }

    @Test
    fun useWipesAfterTheBlock() {
        val mnemonic = Mnemonic.fromEntropy(ByteArray(Mnemonic.ENTROPY_BYTES))
        val buffers = buffersOf(mnemonic)
        mnemonic.use { it.deriveSeed("TREZOR").fill(0) }
        assertTrue(buffers.all(::allZero), "use closes the mnemonic")
    }

    @Test
    fun instanceSeedMatchesThePublishedVector() {
        val seed = Mnemonic.of(artWords).use { it.deriveSeed("TREZOR") }
        try {
            assertEquals(
                "bda85446c68413707090a52022edd26a1c9462295029f2e60cd7c4f2bbd3097" +
                    "170af7a4d73245cafa9c3cca8d561a7c3de6f5d4a10be8ed2a5e608d68f92fcc8",
                seed.toHex(),
            )
        } finally {
            seed.fill(0)
        }
    }
}
