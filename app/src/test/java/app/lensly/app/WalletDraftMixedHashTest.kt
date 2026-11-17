package app.lensly.app

import app.lensly.core.wallet.Mnemonic
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The ceremony's mixed verification hash is one-way: it is never the entropy the words encode, and it is the same value
 * for the same mix. The vectors are SHA-256 values computed outside the code under test, for device entropy byte i equal
 * to (7 * i + 3) mod 256, and for the dice rolls "123456" and "654321".
 */
class WalletDraftMixedHashTest {

    @Test
    fun theShownHashIsTheKnownAnswerNotTheEntropy() {
        val draft = ceremonyDraft()
        draft.mixInput("123456")

        assertEquals(COMMITMENT_HEX, draft.commitmentHex)
        assertEquals(VERIFICATION_HEX, draft.mixedHashHex)
        assertNotEquals(MIXED_ENTROPY_HEX, draft.mixedHashHex)
        assertNotEquals(DEVICE_HEX, draft.mixedHashHex)
        assertEquals(wordsOf(MIXED_ENTROPY_HEX), draft.words)
    }

    @Test
    fun aNewRollChangesTheShownHashAndTheWordsFollowTheMix() {
        val draft = ceremonyDraft()
        draft.mixInput("123456")
        draft.mixInput("654321")

        assertEquals(VERIFICATION_2_HEX, draft.mixedHashHex)
        assertEquals(wordsOf(MIXED_ENTROPY_2_HEX), draft.words)
    }

    @Test
    fun noStringStateOfTheDraftHoldsTheRawEntropyHex() {
        val draft = ceremonyDraft()
        draft.mixInput("123456")

        val stringFields = WalletDraft::class.java.declaredFields.filter { it.type == String::class.java }
        assertTrue(stringFields.size >= 2, "the scan must reach the draft's string state")
        for (field in stringFields) {
            field.isAccessible = true
            val value = field.get(draft) as String? ?: ""
            assertFalse(value.contains(DEVICE_HEX), "${field.name} holds the device entropy")
            assertFalse(value.contains(MIXED_ENTROPY_HEX), "${field.name} holds the mixed entropy")
        }
    }

    /** A draft with a running ceremony whose device entropy is byte i equal to (7 * i + 3) mod 256. */
    private fun ceremonyDraft(): WalletDraft = WalletDraft().also { draft ->
        draft.startCeremony(ByteArray(Mnemonic.ENTROPY_BYTES) { (it * 7 + 3).toByte() })
    }

    /** The 24 words that [entropyHex] encodes, in the order the draft holds them. */
    private fun wordsOf(entropyHex: String): List<String> =
        Mnemonic.fromEntropy(entropyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()).use { it.toList() }

    private companion object {
        const val DEVICE_HEX = "030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dc"
        const val COMMITMENT_HEX = "ab5f8b5cb9435354c7b58603592d5faf081e17ceb05f7a7c67f4b666f12ca457"
        const val MIXED_ENTROPY_HEX = "5ccd9a3ffd343268022158de8f635ec9d47a43368b2bc3ddf22cd2ae4027be7c"
        const val VERIFICATION_HEX = "579f0cfd742bb854ad6142d5a5c7b4bf70f0813e7d1c25099ed1d335841b07f5"
        const val MIXED_ENTROPY_2_HEX = "fd035e8be7427e6ab9d8e4e128d9a0ce55d6cd4a95c25f56aa7ed0208652e9bd"
        const val VERIFICATION_2_HEX = "4f8a47c8444ecc1898b3848f6e6a8a2fcc5ca8e155d60eb8c73061efbd581bbf"
    }
}
