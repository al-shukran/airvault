package app.lensly.ui.sign

import app.airvault.core.model.ChainId
import app.airvault.core.model.Coverage
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.NetworkLabel
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.ReviewSection
import app.airvault.core.model.SummaryPart
import app.lensly.app.ALGORAND_FAMILY
import app.lensly.app.AddressBookEntry
import app.lensly.app.BITCOIN_FAMILY
import app.lensly.app.EVM_FAMILY
import app.lensly.app.InternalAccount
import app.lensly.app.SOLANA_FAMILY
import app.lensly.app.addressKeyOf
import app.lensly.app.addressNotesOf
import app.lensly.app.addressesIn
import app.lensly.app.chainOfAddress
import app.lensly.app.isAddressValue
import app.lensly.app.notesOf
import app.lensly.app.sign.evmCounterparties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Address Book lookup of a review's counterparties, on each chain. A saved address shows its entry (a hit). An address that the
 * Address Book does not hold has no entry (a miss), and its review says "Not in address book".
 */
class CounterpartyLookupTest {
    private val fromAddress = "0x71C7656EC7ab88b098defB751B7401B5f6d8976F"
    private val recipient = "0x9f8F72aA9304c8B593d555F12eF6589cC3A579A2"
    private val spender = "0x1111111254EEB25477B68fb85Ed929f73A960582"

    private fun entry(address: String, name: String) = AddressBookEntry(id = name, address = address, name = name, label = null)

    /** A transfer of 0.01 MON to [recipient], whose arguments name [spender], as the review builder lays it out. */
    private fun evmReview(): Review {
        val chain = ChainId("eip155:10143")
        return Review(
            title = "transfer",
            chain = chain,
            network = NetworkLabel("Monad testnet", testnet = true, detail = "Chain ID 10143"),
            summary = listOf(SummaryPart.Send("0.01", "MON", fromAddress, recipient)),
            facts = emptyList(),
            sections = listOf(
                ReviewSection(
                    "Transaction",
                    listOf(
                        ReviewRow("From", DisplayValue.Address(chain, fromAddress), null),
                        ReviewRow("To", DisplayValue.Address(chain, recipient), null),
                    ),
                ),
                ReviewSection("Arguments", listOf(ReviewRow("spender", DisplayValue.Address(chain, spender), null))),
            ),
            warnings = emptyList(),
            coverage = Coverage(decoded = emptySet(), excluded = emptyMap()),
        )
    }

    @Test
    fun evmCounterpartiesHoldEveryAddressTheReviewNames() {
        val counterparties = evmCounterparties(evmReview())
        assertTrue(counterparties.containsAll(listOf(fromAddress, recipient, spender)))
    }

    @Test
    fun evmSavedRecipientIsAHitWhateverItsLetterCase() {
        val notes = addressNotesOf(evmCounterparties(evmReview()), EVM_FAMILY, emptyMap(), listOf(entry(recipient.lowercase(), "Alice")))
        assertEquals("Alice", notes.notesOf(recipient)?.entry?.name)
    }

    @Test
    fun evmSignersOwnAddressInTheFromRowShowsItsAccount() {
        val own = InternalAccount(displayName = null, autoName = "Main account", label = null)
        val notes = addressNotesOf(evmCounterparties(evmReview()), EVM_FAMILY, mapOf(addressKeyOf(fromAddress) to own), emptyList())
        assertEquals(own, notes.notesOf(fromAddress)?.internal)
    }

    @Test
    fun evmUnsavedSpenderIsAMiss() {
        val notes = addressNotesOf(evmCounterparties(evmReview()), EVM_FAMILY, emptyMap(), listOf(entry(recipient, "Alice")))
        assertNull(notes.notesOf(spender)?.entry)
        assertNull(notes.notesOf(spender))
    }

    @Test
    fun bitcoinSavedOutputIsAHit() {
        val output = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
        val notes = addressNotesOf(listOf(output), BITCOIN_FAMILY, emptyMap(), listOf(entry(output, "Exchange")))
        assertEquals("Exchange", notes.notesOf(output)?.entry?.name)
    }

    @Test
    fun bitcoinUnsavedOutputIsAMiss() {
        val saved = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
        val other = "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7"
        val notes = addressNotesOf(listOf(other), BITCOIN_FAMILY, emptyMap(), listOf(entry(saved, "Exchange")))
        assertNull(notes.notesOf(other))
    }

    @Test
    fun solanaSavedAddressIsAHitAndAnUnsavedOneIsAMiss() {
        val system = "11111111111111111111111111111111"
        val token = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        val counterparties = addressesIn(listOf(system, "1000000", token))
        val notes = addressNotesOf(counterparties, SOLANA_FAMILY, emptyMap(), listOf(entry(system, "System program")))
        assertEquals("System program", notes.notesOf(system)?.entry?.name)
        assertNull(notes.notesOf(token)?.entry)
    }

    @Test
    fun algorandSavedAddressIsAHitAndAnUnsavedOneIsAMiss() {
        val saved = "OSI2442FE4NVQHRW4RVLUWO5AF43TPIUM7DS4PDGB7EMFPJMMU4KNIN3GQ"
        val unsaved = "YEO767URJECB5BKVVIH4ZOBCN5EHGHLU5RCQPRBSCPQ3OBPVMFOYGSM47U"
        val counterparties = addressesIn(listOf(saved, "42", unsaved))
        assertEquals(listOf(saved, unsaved), counterparties)
        val notes = addressNotesOf(counterparties, ALGORAND_FAMILY, emptyMap(), listOf(entry(saved, "Payroll")))
        assertEquals("Payroll", notes.notesOf(saved)?.entry?.name)
        assertNull(notes.notesOf(unsaved)?.entry)
    }

    @Test
    fun aBitcoinAddressIsAnAddressOfBitcoinAndNotSolana() {
        val bitcoin = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
        assertTrue(isAddressValue(bitcoin))
        assertEquals(BITCOIN_FAMILY, chainOfAddress(bitcoin))
    }

    @Test
    fun aLowercaseEvmAddressIsStillAnAddress() {
        assertTrue(isAddressValue(recipient.lowercase()))
    }

    @Test
    fun anAmountOrAHashIsNotLookedUp() {
        assertFalse(isAddressValue("1000000"))
        assertFalse(isAddressValue("0x" + "ab".repeat(32)))
    }
}
