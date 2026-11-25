package app.lensly.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressLookalikeTest {
    private val evmSaved = "0x1234567890abcdef1234567890abcdef12345678"
    private val evmFake = "0x1234" + "a".repeat(32) + "5678"

    @Test
    fun evmSharesTheEndsOfTheBodyAfter0x() {
        assertTrue(looksLikeAddress(evmFake, evmSaved, EVM_FAMILY))
        assertTrue(looksLikeAddress("0x1234" + "A".repeat(32) + "5678", evmSaved, EVM_FAMILY))
        // "0x12" is not part of the compared four.
        assertFalse(looksLikeAddress("0x1299" + "a".repeat(32) + "5678", evmSaved, EVM_FAMILY))
        assertFalse(looksLikeAddress("0x1234" + "a".repeat(32) + "5679", evmSaved, EVM_FAMILY))
    }

    @Test
    fun theSameAddressIsNotALookalikeOfItself() {
        assertFalse(looksLikeAddress(evmSaved, "0x" + evmSaved.drop(2).uppercase(), EVM_FAMILY))
    }

    @Test
    fun bitcoinBech32DropsTheHrp() {
        val saved = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        assertTrue(looksLikeAddress("bc1qar0s" + "x".repeat(26) + "5mdq", saved, BITCOIN_FAMILY))
        // Sharing "bc1q" alone is not enough: the hrp is not a compared end.
        assertFalse(looksLikeAddress("bc1qbbbb" + "x".repeat(26) + "5mdq", saved, BITCOIN_FAMILY))
    }

    @Test
    fun bitcoinBase58DropsTheVersionCharacter() {
        val saved = "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"
        assertTrue(looksLikeAddress("1BvBM" + "x".repeat(24) + "NVN2", saved, BITCOIN_FAMILY))
        assertFalse(looksLikeAddress("1BvBM" + "x".repeat(24) + "NVN3", saved, BITCOIN_FAMILY))
        // A different version character with the same body is still the same ends.
        assertTrue(looksLikeAddress("3BvBM" + "x".repeat(24) + "NVN2", saved, BITCOIN_FAMILY))
    }

    @Test
    fun solanaAndAlgorandAreComparedExactlyAsWritten() {
        val sol = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        assertTrue(looksLikeAddress("9WzD" + "x".repeat(36) + "AWWM", sol, SOLANA_FAMILY))
        assertFalse(looksLikeAddress("9wzD" + "x".repeat(36) + "AWWM", sol, SOLANA_FAMILY))
        val alg = "VCMJKWOY5P5P7SKMZFFOCEROPJCZOTIJMNIYNUCKH7LRO45JMJP6UYBIJA"
        assertTrue(looksLikeAddress("VCMJ" + "X".repeat(50) + "BIJA", alg, ALGORAND_FAMILY))
        assertFalse(looksLikeAddress("VCMJ" + "X".repeat(50) + "bija", alg, ALGORAND_FAMILY))
    }

    @Test
    fun anEntryIsFoundOnlyOnItsFamilyAndNeverForASavedAddress() {
        val entry = AddressBookEntry(id = "1", address = evmSaved, name = "Alice", label = null)
        assertEquals(entry, lookalikeEntryFor(evmFake, EVM_FAMILY, listOf(entry)))
        assertNull(lookalikeEntryFor(evmSaved, EVM_FAMILY, listOf(entry)))
        assertNull(lookalikeEntryFor(evmFake, SOLANA_FAMILY, listOf(entry)))
        assertNull(lookalikeEntryFor("0x9999999999999999999999999999999999999999", EVM_FAMILY, listOf(entry)))
    }
}
