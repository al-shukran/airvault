package app.lensly.app

import app.airvault.chains.algorand.AlgoAddress
import app.airvault.chains.algorand.AlgorandAddressCodec
import app.airvault.chains.algorand.algorandAddressOf
import app.airvault.chains.algorand.algorandMainnet
import app.airvault.chains.bitcoin.BitcoinAddressCodec
import app.airvault.chains.bitcoin.bitcoinMainnet
import app.airvault.chains.bitcoin.bitcoinTestnet4
import app.airvault.chains.evm.EvmAddressCodec
import app.airvault.chains.evm.ethereumMainnet
import app.airvault.chains.solana.SolanaAddressCodec
import app.airvault.chains.solana.solanaAddressOf
import app.airvault.chains.solana.solanaMainnet
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import fr.acinq.bitcoin.Base58
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
    fun everyBitcoinScriptTypeOnMainnetAndTestnetIsValidOnItsOwnNetwork() {
        listOf(p2pkhMain, p2shMain, p2wpkhMain, p2wshMain, p2trMain).forEach { address ->
            assertEquals(address, canonicalAddressOf(address))
            assertEquals(bitcoinMainnet, chainOfAddress(address))
        }
        listOf(p2pkhTest, p2shTest, p2wpkhTest, p2wshTest, p2trTest).forEach { address ->
            assertEquals(address, canonicalAddressOf(address))
            assertEquals(bitcoinTestnet4, chainOfAddress(address))
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
        assertEquals(SOLANA_FAMILY, chainOfAddress(solana))
        assertEquals(solanaSystem, canonicalAddressOf(solanaSystem))
        assertEquals(SOLANA_FAMILY, chainOfAddress(solanaSystem))
    }

    @Test
    fun aSolanaAddressIsA32ByteKeyAndNotALegacyBitcoinAddress() {
        // A legacy Bitcoin address has the shape of a Solana address: 34 base58 characters. It decodes to 25 bytes, not 32.
        assertNull(solanaAddressOf(p2pkhMain))
        assertEquals(BITCOIN_FAMILY, chainOfAddress(p2pkhMain))
        // A zero is not a base58 character, so a 32-character value with one is not a Solana address.
        assertNull(solanaAddressOf("0" + "1".repeat(31)))
    }

    @Test
    fun anAlgorandAddressIsKeptAsWrittenAndIsOnTheAlgorandChain() {
        assertEquals(algorand, canonicalAddressOf(algorand))
        assertEquals(ALGORAND_FAMILY, chainOfAddress(algorand))
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
        assertEquals(EVM_FAMILY, chainOfAddress(evm))
        assertNull(canonicalAddressOf("0x5aaeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))
    }

    @Test
    fun aTextThatIsNoAddressOfAnyChainHasNoChain() {
        assertNull(chainOfAddress("not an address"))
        assertNull(chainOfAddress(""))
        assertNull(canonicalAddressOn(p2pkhMain, EVM_FAMILY))
        assertNull(canonicalAddressOn(evm, BITCOIN_FAMILY))
        assertNull(canonicalAddressOn(solana, ALGORAND_FAMILY))
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
        assertEquals("Alice", addressBookEntryFor(evm.lowercase(), EVM_FAMILY, book)?.name)
        assertEquals("Alice", addressBookEntryFor(evm.uppercase().replace("0X", "0x"), EVM_FAMILY, book)?.name)
        // A Bitcoin, Solana or Algorand address matches exactly.
        assertEquals("Bob", addressBookEntryFor(p2wpkhMain, BITCOIN_FAMILY, book)?.name)
        assertNull(addressBookEntryFor(p2wpkhMain.uppercase(), BITCOIN_FAMILY, book))
        assertEquals("Token", addressBookEntryFor(solana, SOLANA_FAMILY, book)?.name)
        assertNull(addressBookEntryFor(solana.lowercase(), SOLANA_FAMILY, book))
        assertEquals("Payroll", addressBookEntryFor(algorand, ALGORAND_FAMILY, book)?.name)
        assertNull(addressBookEntryFor(algorand.lowercase(), ALGORAND_FAMILY, book))
    }

    @Test
    fun anEntryIsFoundOnlyOnItsOwnChain() {
        val book = listOf(entry("s", solana, "Token"))
        assertEquals("Token", addressBookEntryFor(solana, SOLANA_FAMILY, book)?.name)
        assertNull(addressBookEntryFor(solana, EVM_FAMILY, book))
        assertNull(addressBookEntryFor(solana, BITCOIN_FAMILY, book))
        assertNull(addressBookEntryFor(solana, ALGORAND_FAMILY, book))
    }

    @Test
    fun aBitcoinMainnetAndATestnetAddressAreTwoEntries() {
        val book = listOf(entry("main", p2wpkhMain, "Main"))
        val saved = addressBookEntryOf("test", p2wpkhTest, "Testnet", "", book)
        assertEquals(p2wpkhTest, saved.address)
        assertEquals(bitcoinTestnet4, saved.chain)
    }

    @Test
    fun aFormEntryOnEachChainIsSavedInItsCanonicalForm() {
        assertEquals(p2wpkhMain, addressBookEntryOf("1", p2wpkhMain.uppercase(), "Bob", "", emptyList()).address)
        assertEquals(solana, addressBookEntryOf("2", " $solana ", "Token", "", emptyList()).address)
        assertEquals(algorand, addressBookEntryOf("3", algorand, "Payroll", "", emptyList()).address)
        assertEquals(evm, addressBookEntryOf("4", evm.lowercase(), "Alice", "", emptyList()).address)
        assertEquals(
            listOf(BITCOIN_FAMILY, SOLANA_FAMILY, ALGORAND_FAMILY, EVM_FAMILY),
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
    fun eachChainDerivesItsAddressFromAPublicKey() {
        // The secp256k1 generator is the public key of private key 1, whose addresses are published: the BIP-173 native segwit address
        // on mainnet and testnet4, and the Ethereum address.
        val compressed = hex("0279BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798")
        val uncompressed = hex(
            "04" + "79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798" + "483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8",
        )
        assertEquals("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", BitcoinAddressCodec.fromPublicKey(bitcoinMainnet, compressed))
        assertEquals(p2wpkhTest, BitcoinAddressCodec.fromPublicKey(bitcoinTestnet4, compressed))
        assertEquals("0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf", EvmAddressCodec.fromPublicKey(ethereumMainnet, uncompressed))
        // A Solana and an Algorand address are their public key written out, so each one reads back to itself.
        assertEquals(solana, SolanaAddressCodec.fromPublicKey(solanaMainnet, Base58.decode(solana)))
        assertEquals(algorand, AlgorandAddressCodec.fromPublicKey(algorandMainnet, AlgoAddress.decode(algorand)))
    }

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

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
