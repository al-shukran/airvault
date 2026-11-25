package app.lensly.app

import app.lensly.ui.components.groupAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    fun anUppercaseBech32AddressIsStillCaughtAndIsNeverALookalikeOfItself() {
        val saved = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        assertTrue(looksLikeAddress(("bc1qar0s" + "x".repeat(26) + "5mdq").uppercase(), saved, BITCOIN_FAMILY))
        assertFalse(looksLikeAddress(saved.uppercase(), saved, BITCOIN_FAMILY))
        val entry = AddressBookEntry(id = "1", address = saved, name = "Alice", label = null)
        assertNull(lookalikeEntryFor(saved.uppercase(), BITCOIN_FAMILY, listOf(entry)))
        assertEquals(entry, lookalikeEntryFor(("bc1qar0s" + "x".repeat(26) + "5mdq").uppercase(), BITCOIN_FAMILY, listOf(entry)))
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

    @Test
    fun onlyTheFirstSimilarEntryIsReturned() {
        val first = AddressBookEntry(id = "1", address = evmSaved, name = "First", label = null)
        val second = AddressBookEntry(id = "2", address = "0x1234" + "b".repeat(32) + "5678", name = "Second", label = null)
        assertEquals(first, lookalikeEntryFor("0x1234" + "c".repeat(32) + "5678", EVM_FAMILY, listOf(first, second)))
        assertEquals(second, lookalikeEntryFor("0x1234" + "c".repeat(32) + "5678", EVM_FAMILY, listOf(second, first)))
    }

    @Test
    fun anEntryOnTheExactChainWinsOverAnotherOfTheFamily() {
        // The two share the first and last 4 characters of their bodies, and sit on Bitcoin mainnet and testnet.
        val mainnet = AddressBookEntry(id = "1", address = "bc1qpzry9x8gf2tvdw0s3jn54khce6mupzrywnvy0t", name = "Main", label = null)
        val testnet = AddressBookEntry(id = "2", address = "tb1qpzry9x8gf2tvdw0s3jn54khce6mudn9ewfvy0t", name = "Test", label = null)
        assertNotNull(testnet.chain)
        assertNotEquals(mainnet.chain, testnet.chain)
        val fake = "bc1qpzry" + "x".repeat(26) + "vy0t"
        assertEquals(mainnet, lookalikeEntryFor(fake, mainnet.chain!!, listOf(testnet, mainnet)))
        assertEquals(testnet, lookalikeEntryFor(fake, testnet.chain!!, listOf(mainnet, testnet)))
    }

    @Test
    fun theWarningNamesTheSavedEntryAsPlainText() {
        val entry = AddressBookEntry(id = "1", address = evmSaved, name = "\u202EAlice\n", label = null)
        assertEquals("Looks like ${isolated("Alice")} but differs", lookalikeWarningText(entry))
        assertEquals("Looks like ${isolated(groupAddress(evmSaved))} but differs", lookalikeWarningText(entry.copy(name = "\u200B")))
    }

    @Test
    fun anAccountOfThisDeviceIsALookalikeCandidateUnderItsName() {
        val own = mapOf(addressKeyOf(evmSaved) to InternalAccount(displayName = null, autoName = "Account 1", label = null))
        val found = lookalikeEntryFor(evmFake, EVM_FAMILY, emptyList(), own)
        assertEquals("Account 1", found?.name)
        assertEquals(addressKeyOf(evmSaved), found?.address)
        // The account itself, another family, and an address that is not close are no lookalike.
        assertNull(lookalikeEntryFor(evmSaved, EVM_FAMILY, emptyList(), own))
        assertNull(lookalikeEntryFor(evmFake, SOLANA_FAMILY, emptyList(), own))
        assertNull(lookalikeEntryFor("0x9999999999999999999999999999999999999999", EVM_FAMILY, emptyList(), own))
        // A saved entry that looks alike is named before an account.
        val entry = AddressBookEntry(id = "1", address = "0x1234" + "b".repeat(32) + "5678", name = "Bob", label = null)
        assertEquals(entry, lookalikeEntryFor(evmFake, EVM_FAMILY, listOf(entry), own))
    }

    @Test
    fun addressNotesFlagALookalikeOfAnOwnAccount() {
        val own = mapOf(addressKeyOf(evmSaved) to InternalAccount(displayName = "Main", autoName = "Account 1", label = null))
        assertEquals("Main", addressNotesOf(evmFake, EVM_FAMILY, own, emptyList())?.lookalike?.name)
    }

    @Test
    fun aSolanaAddressStartingWithBc1KeepsItsCase() {
        // 43 base58 characters starting with "b" decode to 32 bytes, so this is a valid Solana address that starts like a bech32 one.
        val solana = "bc1ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopq"
        assertEquals(SOLANA_FAMILY.namespace, chainOfAddress(solana)?.namespace)
        assertEquals(solana, addressKeyOf(solana))
        assertNotEquals(addressKeyOf(solana), addressKeyOf(solana.lowercase()))
        val bech32 = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        assertEquals(bech32, addressKeyOf(bech32.uppercase()))
    }
}
