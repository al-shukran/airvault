package app.lensly.core.wallet

import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class MnemonicTest {

    // BIP39 vector for 256 zero bits of entropy.
    private val artMnemonic = List(23) { "abandon" } + "art"

    @Test
    fun zeroEntropyEncodesToBip39Vector() {
        assertEquals(artMnemonic, Mnemonic.fromEntropy(ByteArray(32)))
    }

    @Test
    fun seedMatchesBip39VectorWithTrezorPassphrase() {
        // Source: trezor/python-mnemonic vectors.json, english entry for 32 zero bytes.
        assertEquals(
            "bda85446c68413707090a52022edd26a1c9462295029f2e60cd7c4f2bbd3097" +
                "170af7a4d73245cafa9c3cca8d561a7c3de6f5d4a10be8ed2a5e608d68f92fcc8",
            Mnemonic.toSeed(artMnemonic, "TREZOR").toHex(),
        )
    }

    @Test
    fun passphraseIsNfkdNormalizedBeforeSeeding() {
        // BIP39 normalization vector (Japanese passphrase). Independent check: Python
        // hashlib.pbkdf2_hmac('sha512', mnemonic, 'mnemonic' + NFKD(passphrase), 2048, 64).
        // Without NFKD, web3j gives 90e02c4d... instead.
        val passphrase = "㎍ガバヴァぱばぐゃょぢ"
        assertEquals(
            "07dab8e26c69b6dc92c2371a3cd8d653f9f896578c5eb2b18515398605c43ed1" +
                "19c6c8188b80689dbd2abbe937445c6cdd8ede7ae13b34d293c186467ce3312f",
            Mnemonic.toSeed(artMnemonic, passphrase).toHex(),
        )
    }

    @Test
    fun generateTakesEntropyFromSuppliedRandom() {
        val zeros = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) = bytes.fill(0)
        }
        assertEquals(artMnemonic, Mnemonic.generate(zeros))
    }

    @Test
    fun generatedMnemonicsAreValid24Words() {
        repeat(20) {
            val words = Mnemonic.generate()
            assertEquals(24, words.size)
            assertTrue(Mnemonic.isValid(words))
        }
    }

    @Test
    fun generatedMnemonicsDiffer() {
        assertNotEquals(Mnemonic.generate(), Mnemonic.generate())
    }

    @Test
    fun acceptsValidMnemonic() {
        assertTrue(Mnemonic.isValid(artMnemonic))
    }

    @Test
    fun rejectsBadChecksum() {
        // 24 x "abandon" is all-zero entropy, whose checksum is 0x66, not 0x00.
        assertFalse(Mnemonic.isValid(List(24) { "abandon" }))
        // Changing the last word breaks the checksum.
        assertFalse(Mnemonic.isValid(artMnemonic.dropLast(1) + "abandon"))
        // Changing the first word changes the entropy without fixing the checksum.
        assertFalse(Mnemonic.isValid(listOf("ability") + artMnemonic.drop(1)))
    }

    @Test
    fun rejectsTwelveWords() {
        val twelve = artMnemonic.take(12)
        assertFalse(Mnemonic.isValid(twelve))
        assertFailsWith<IllegalArgumentException> { Mnemonic.toSeed(twelve) }
    }

    @Test
    fun rejectsUnknownWord() {
        assertFalse(Mnemonic.isValid(listOf("notaword") + artMnemonic.drop(1)))
    }

    @Test
    fun toSeedRejectsBadChecksum() {
        assertFailsWith<IllegalArgumentException> {
            Mnemonic.toSeed(artMnemonic.dropLast(1) + "abandon")
        }
    }

    @Test
    fun fromEntropyRejectsWrongLength() {
        assertFailsWith<IllegalArgumentException> { Mnemonic.fromEntropy(ByteArray(16)) }
    }
}
