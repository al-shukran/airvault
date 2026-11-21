package app.lensly.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test

private const val EVM = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
/** The same address with its last letter in the wrong case, so its checksum is wrong. */
private const val EVM_BAD_CHECKSUM = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD"
private const val BITCOIN = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
private const val SOLANA = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
private const val ALGORAND = "OSI2442FE4NVQHRW4RVLUWO5AF43TPIUM7DS4PDGB7EMFPJMMU4KNIN3GQ"

private fun entry(id: String, address: String) = AddressBookEntry(id = id, address = address, name = "Saved", label = null)

class AddressBookScanTest {

    @Test
    fun aPlainAddressOfEachChainIsItsOwnCanonicalForm() {
        listOf(EVM, BITCOIN, SOLANA, ALGORAND).forEach { address ->
            assertEquals(address, addressOfScan(address), address)
        }
    }

    @Test
    fun aPlainAddressIsTrimmedBeforeItIsRead() {
        assertEquals(EVM, addressOfScan("  $EVM\n"))
    }

    @Test
    fun aLowerCaseEvmAddressIsReadInItsChecksumForm() {
        assertEquals(EVM, addressOfScan(EVM.lowercase()))
    }

    @Test
    fun anEip681LinkNamesItsAddressWithOrWithoutAChainId() {
        assertEquals(EVM, addressOfScan("ethereum:$EVM"))
        assertEquals(EVM, addressOfScan("ethereum:$EVM@1"))
        assertEquals(EVM, addressOfScan("ethereum:$EVM@137"))
        assertEquals(EVM, addressOfScan(" ethereum:$EVM@10143 "))
    }

    @Test
    fun anEip681LinkWithAMalformedChainIdIsRefused() {
        assertNull(addressOfScan("ethereum:$EVM@"))
        assertNull(addressOfScan("ethereum:$EVM@0"))
        assertNull(addressOfScan("ethereum:$EVM@01"))
        assertNull(addressOfScan("ethereum:$EVM@1x"))
        assertNull(addressOfScan("ethereum:$EVM@9999999999999999999"))
    }

    @Test
    fun anEip681LinkWithAnythingElseAfterTheAddressIsRefused() {
        assertNull(addressOfScan("ethereum:$EVM?value=1"))
        assertNull(addressOfScan("ethereum:$EVM/transfer"))
        assertNull(addressOfScan("ethereum:$EVM@1?value=1"))
    }

    @Test
    fun anEip681LinkNeedsAWellFormedEvmAddress() {
        assertNull(addressOfScan("ethereum:$BITCOIN"))
        assertNull(addressOfScan("ethereum:${EVM.dropLast(1)}"))
        assertNull(addressOfScan("ethereum:$EVM_BAD_CHECKSUM"))
        assertNull(addressOfScan("ethereum:"))
        assertNull(addressOfScan("ETHEREUM:$EVM"))
    }

    @Test
    fun anythingThatIsNotAnAddressIsRefused() {
        assertNull(addressOfScan(""))
        assertNull(addressOfScan("hello"))
        assertNull(addressOfScan("0x123"))
        assertNull(addressOfScan("$EVM extra"))
        assertNull(addressOfScan("$EVM_BAD_CHECKSUM"))
        assertNull(addressOfScan("https://example.com/$EVM"))
        assertNull(addressOfScan("bitcoin:$BITCOIN"))
    }

    @Test
    fun aScannedAddressOfAnEntryOpensThatEntryToEdit() {
        val existing = entry("entry-evm", EVM)
        val target = scanTargetOf("ethereum:${EVM.lowercase()}@1", listOf(existing))
        assertIs<ScanTarget.Existing>(target)
        assertEquals(existing, target.entry)
    }

    @Test
    fun aScannedBitcoinAddressOfAnEntryOpensThatEntryToEdit() {
        val existing = entry("entry-btc", BITCOIN)
        val target = scanTargetOf(BITCOIN, listOf(entry("entry-evm", EVM), existing))
        assertIs<ScanTarget.Existing>(target)
        assertEquals(existing, target.entry)
    }

    @Test
    fun aScannedAddressTheBookDoesNotHoldStartsANewEntry() {
        val book = listOf(entry("entry-evm", EVM))
        assertEquals(ScanTarget.New(BITCOIN), scanTargetOf(BITCOIN, book))
        assertEquals(ScanTarget.New(SOLANA), scanTargetOf(SOLANA, book))
        assertEquals(ScanTarget.New(ALGORAND), scanTargetOf(ALGORAND, book))
        assertEquals(ScanTarget.New(EVM), scanTargetOf(EVM, emptyList()))
    }

    @Test
    fun aRefusedCodeHasNoTarget() {
        assertNull(scanTargetOf("https://example.com", listOf(entry("entry-evm", EVM))))
        assertNull(scanTargetOf("ethereum:$EVM@", emptyList()))
    }
}
