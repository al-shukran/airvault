package app.lensly.app

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Address Book on every chain: the addresses it takes and their canonical form, which chain each one is on, the lookup that matches an
 * address on its own chain, and the store that keeps notes. The addresses are known-good ones, each checked by its own checksum or length,
 * not by the code under test.
 */
class MultiChainAddressBookTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = MemoryKeystore()
    private val vaultKey = ByteArray(32) { (it * 7 + 1).toByte() }

    // A session zeroes the key array it is given when it closes, so each session gets its own copy.
    private fun open(): VaultSession = VaultSession(VaultLayout(tmp.root), keys, vaultKey.copyOf())

    // Bitcoin: every script type that Mainnet and Testnet4 use.
    private val p2pkhMain = "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"
    private val p2shMain = "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy"
    private val p2wpkhMain = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
    private val p2wshMain = "bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3"
    private val p2trMain = "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr"
    private val p2pkhTest = "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"
    private val p2shTest = "2MzQwSSnBHWHqSAqtTVQ6v47XtaisrJa1Vc"
    private val p2wpkhTest = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
    private val p2wshTest = "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7"
    private val p2trTest = "tb1pqqqqp399et2xygdj5xreqhjjvcmzhxw4aywxecjdzew6hylgvsesf3hn0c"

    private val evm = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val solana = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    private val solanaSystem = "11111111111111111111111111111111"
    private val algorand = "OSI2442FE4NVQHRW4RVLUWO5AF43TPIUM7DS4PDGB7EMFPJMMU4KNIN3GQ"

    private fun entry(id: String, address: String, name: String) = AddressBookEntry(id = id, address = address, name = name, label = null)

    @Test
    fun everyBitcoinScriptTypeOnMainnetAndTestnetIsValidOnTheBitcoinChain() {
        listOf(p2pkhMain, p2shMain, p2wpkhMain, p2wshMain, p2trMain, p2pkhTest, p2shTest, p2wpkhTest, p2wshTest, p2trTest)
            .forEach { address ->
                assertEquals(address, canonicalAddressOf(address))
                assertEquals(AddressChain.BITCOIN, chainOfAddress(address))
            }
        assertEquals(BtcNetwork.MAINNET, btcNetworkOf(p2wpkhMain))
        assertEquals(BtcNetwork.MAINNET, btcNetworkOf(p2pkhMain))
        assertEquals(BtcNetwork.TESTNET4, btcNetworkOf(p2wpkhTest))
        assertEquals(BtcNetwork.TESTNET4, btcNetworkOf(p2trTest))
        assertNull(btcNetworkOf(evm))
    }

    @Test
    fun aBech32BitcoinAddressIsCanonicalInLowerCase() {
        assertEquals(p2wpkhMain, canonicalAddressOf(p2wpkhMain.uppercase()))
        assertEquals(p2trTest, canonicalAddressOf("  ${p2trTest.uppercase()} "))
    }

    @Test
    fun aSolanaAddressIsKeptAsWrittenAndIsOnTheSolanaChain() {
        assertEquals(solana, canonicalAddressOf("  $solana "))
        assertEquals(AddressChain.SOLANA, chainOfAddress(solana))
        assertEquals(solanaSystem, canonicalAddressOf(solanaSystem))
        assertEquals(AddressChain.SOLANA, chainOfAddress(solanaSystem))
    }

    @Test
    fun aSolanaAddressIsA32ByteKeyAndNotALegacyBitcoinAddress() {
        // A legacy Bitcoin address has the shape of a Solana address: 34 base58 characters. It decodes to 25 bytes, not 32.
        assertNull(solanaAddressOf(p2pkhMain))
        assertEquals(AddressChain.BITCOIN, chainOfAddress(p2pkhMain))
        // A zero is not a base58 character, so a 32-character value with one is not a Solana address.
        assertNull(solanaAddressOf("0" + "1".repeat(31)))
    }

    @Test
    fun anAlgorandAddressIsKeptAsWrittenAndIsOnTheAlgorandChain() {
        assertEquals(algorand, canonicalAddressOf(algorand))
        assertEquals(AddressChain.ALGORAND, chainOfAddress(algorand))
    }

    @Test
    fun anAlgorandAddressWithAWrongChecksumIsRefused() {
        // Changing the last letter breaks the checksum. Lower case is not the Algorand form either.
        val last = algorand.last()
        val broken = algorand.dropLast(1) + (if (last == 'A') 'B' else 'A')
        assertNull(algorandAddressOf(broken))
        assertNull(canonicalAddressOf(broken))
        assertNull(canonicalAddressOf(algorand.lowercase()))
    }

    @Test
    fun anEvmAddressIsCanonicalInChecksumFormAndOnTheEvmChain() {
        assertEquals(evm, canonicalAddressOf(evm.lowercase()))
        assertEquals(AddressChain.EVM, chainOfAddress(evm))
        assertNull(canonicalAddressOf("0x5aaeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))
    }

    @Test
    fun aTextThatIsNoAddressOfAnyChainHasNoChain() {
        assertNull(chainOfAddress("not an address"))
        assertNull(chainOfAddress(""))
        assertNull(canonicalAddressOn(p2pkhMain, AddressChain.EVM))
        assertNull(canonicalAddressOn(evm, AddressChain.BITCOIN))
        assertNull(canonicalAddressOn(solana, AddressChain.ALGORAND))
    }

    @Test
    fun theLookupNormalizesEachChainByItsOwnRules() {
        val book = listOf(
            entry("e", evm, "Alice"),
            entry("b", p2wpkhMain, "Bob"),
            entry("s", solana, "Token"),
            entry("a", algorand, "Payroll"),
        )
        // An EVM address matches in any letter case.
        assertEquals("Alice", addressBookEntryFor(evm.lowercase(), AddressChain.EVM, book)?.name)
        assertEquals("Alice", addressBookEntryFor(evm.uppercase().replace("0X", "0x"), AddressChain.EVM, book)?.name)
        // A Bitcoin, Solana or Algorand address matches exactly.
        assertEquals("Bob", addressBookEntryFor(p2wpkhMain, AddressChain.BITCOIN, book)?.name)
        assertNull(addressBookEntryFor(p2wpkhMain.uppercase(), AddressChain.BITCOIN, book))
        assertEquals("Token", addressBookEntryFor(solana, AddressChain.SOLANA, book)?.name)
        assertNull(addressBookEntryFor(solana.lowercase(), AddressChain.SOLANA, book))
        assertEquals("Payroll", addressBookEntryFor(algorand, AddressChain.ALGORAND, book)?.name)
        assertNull(addressBookEntryFor(algorand.lowercase(), AddressChain.ALGORAND, book))
    }

    @Test
    fun anEntryIsFoundOnlyOnItsOwnChain() {
        val book = listOf(entry("s", solana, "Token"))
        assertEquals("Token", addressBookEntryFor(solana, AddressChain.SOLANA, book)?.name)
        assertNull(addressBookEntryFor(solana, AddressChain.EVM, book))
        assertNull(addressBookEntryFor(solana, AddressChain.BITCOIN, book))
        assertNull(addressBookEntryFor(solana, AddressChain.ALGORAND, book))
    }

    @Test
    fun aBitcoinMainnetAndATestnetAddressAreTwoEntries() {
        val book = listOf(entry("main", p2wpkhMain, "Main"))
        val saved = addressBookEntryOf("test", p2wpkhTest, "Testnet", "", book)
        assertEquals(p2wpkhTest, saved.address)
        assertEquals(AddressChain.BITCOIN, saved.chain)
    }

    @Test
    fun aFormEntryOnEachChainIsSavedInItsCanonicalForm() {
        assertEquals(p2wpkhMain, addressBookEntryOf("1", p2wpkhMain.uppercase(), "Bob", "", emptyList()).address)
        assertEquals(solana, addressBookEntryOf("2", " $solana ", "Token", "", emptyList()).address)
        assertEquals(algorand, addressBookEntryOf("3", algorand, "Payroll", "", emptyList()).address)
        assertEquals(evm, addressBookEntryOf("4", evm.lowercase(), "Alice", "", emptyList()).address)
        assertEquals(
            listOf(AddressChain.BITCOIN, AddressChain.SOLANA, AddressChain.ALGORAND, AddressChain.EVM),
            listOf(p2wpkhMain, solana, algorand, evm).map { addressBookEntryOf("x", it, "Name", "", emptyList()).chain },
        )
    }

    @Test
    fun anAddressIsInTheBookOncePerChain() {
        assertFailsWith<IllegalArgumentException> {
            addressBookEntryOf("new", solana, "Again", "", listOf(entry("s", solana, "Token")))
        }
        assertFailsWith<IllegalArgumentException> {
            addressBookEntryOf("new", evm.lowercase(), "Again", "", listOf(entry("e", evm, "Alice")))
        }
        // Saving the entry again, under its own id, is not a second entry.
        assertEquals("Token", addressBookEntryOf("s", solana, "Token", "", listOf(entry("s", solana, "Token"))).name)
    }

    @Test
    fun notesAreTrimmedKeptAndLimited() {
        val saved = addressBookEntryOf("1", algorand, "Payroll", "", emptyList(), notes = "  Monthly payroll, Q4  ")
        assertEquals("Monthly payroll, Q4", saved.notes)
        assertNull(addressBookEntryOf("2", algorand, "Payroll", "", emptyList()).notes)
        assertFailsWith<IllegalArgumentException> {
            addressBookEntryOf("3", algorand, "Payroll", "", emptyList(), notes = "x".repeat(ADDRESS_NOTES_MAX_LENGTH + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            addressBookEntryOf("4", algorand, "Payroll", "", emptyList(), notes = "bell\u0007")
        }
        assertTrue(isValidAddressNotes("x".repeat(ADDRESS_NOTES_MAX_LENGTH)))
    }

    @Test
    fun everyChainAndTheNotesSurviveReopeningTheVault() {
        val book = listOf(
            entry("e", evm, "Alice").copy(notes = "Exchange deposit"),
            entry("b", p2trTest, "Bob"),
            entry("s", solana, "Token"),
            entry("a", algorand, "Payroll").copy(label = "Monthly", notes = "Paid on the 1st"),
        )
        AddressBookStore(open()).also { store -> book.forEach(store::put) }

        assertEquals(book, AddressBookStore(open()).load())
    }
}
