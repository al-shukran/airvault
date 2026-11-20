package app.lensly.ui.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.airvault.core.model.ChainId
import app.lensly.app.ALGORAND_FAMILY
import app.lensly.app.AddressBookEntry
import app.lensly.app.BITCOIN_FAMILY
import app.lensly.app.EVM_FAMILY
import app.lensly.app.InternalAccount
import app.lensly.app.SOLANA_FAMILY
import app.lensly.app.addressKeyOf
import app.lensly.app.sign.BitcoinRecordFacts
import app.lensly.app.sign.BitcoinRecordOutput
import app.lensly.app.sign.ReviewKind
import app.lensly.app.sign.ReviewOutcome
import app.lensly.app.sign.ReviewRecord
import app.lensly.core.registry.NetworkEntry
import app.lensly.ui.components.groupAddress
import app.lensly.ui.theme.LumenTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Activity detail sheet: each address of a record is a row that shows what the Address Book holds for it, and a tap on it closes the
 * sheet and opens that address on its chain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h914dp-port-mdpi")
class HistoryDetailSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val sender = "0x71c7656ec7ab88b098defb751b7401b5f6d8976f"
    private val recipient = "0x9f8f72aa9304c8b593d555f12ef6589cc3a579a2"
    private val bitcoinPaid = "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"
    private val bitcoinChange = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
    private val solanaProgram = "11111111111111111111111111111111"
    private val algorandSender = "OSI2442FE4NVQHRW4RVLUWO5AF43TPIUM7DS4PDGB7EMFPJMMU4KNIN3GQ"
    private val algorandCreator = "YEO767URJECB5BKVVIH4ZOBCN5EHGHLU5RCQPRBSCPQ3OBPVMFOYGSM47U"

    private fun evmRecord() = record(
        networkName = "Monad Testnet",
        address = sender,
        to = recipient,
        amount = "0.01 MON",
    )

    private fun record(
        kind: ReviewKind = ReviewKind.TRANSACTION,
        networkName: String? = null,
        address: String? = null,
        to: String? = null,
        amount: String? = null,
        params: List<Pair<String, String>> = emptyList(),
        bitcoin: BitcoinRecordFacts? = null,
    ) = ReviewRecord(
        atMs = 1_700_000_000_000L,
        outcome = ReviewOutcome.SIGNED,
        kind = kind,
        networkName = networkName,
        accountIndex = 0,
        address = address,
        function = null,
        to = to,
        amount = amount,
        tokenContract = null,
        fee = emptyList(),
        params = params,
        origin = null,
        refusal = null,
        dataHash = "00",
        bitcoin = bitcoin,
    )

    private fun setSheet(
        record: ReviewRecord,
        book: List<AddressBookEntry> = emptyList(),
        accounts: Map<String, InternalAccount> = emptyMap(),
        networks: List<NetworkEntry> = sampleNetworks,
        onOpen: (String, ChainId) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            LumenTheme {
                HistoryDetailSheet(
                    record = record,
                    networks = networks,
                    addressBook = book,
                    accounts = accounts,
                    onDismiss = {},
                    onOpenAddress = onOpen,
                )
            }
        }
    }

    @Test
    fun eachAddressShowsItsNameLabelAndNotesOrThatItIsNotInTheAddressBook() {
        val book = listOf(AddressBookEntry(id = "1", address = recipient, name = "Alice", label = "Exchange", notes = "Deposits only"))

        setSheet(evmRecord(), book)

        compose.onNodeWithText("Alice").assertIsDisplayed()
        compose.onNodeWithText("Exchange").assertIsDisplayed()
        compose.onNodeWithText("Deposits only").assertIsDisplayed()
        // The sender is not in the Address Book, so its row says so. The recipient is, so only one row says it.
        compose.onAllNodesWithText("Not in address book").assertCountEquals(1)
        compose.onAllNodesWithText("Add to Address Book").assertCountEquals(0)
    }

    /** An address row takes the logo of the registry network its record names. A network the registry does not list shows a letter instead. */
    @Test
    fun aRecordOnARegisteredNetworkShowsItsLogoNotALetter() {
        setSheet(evmRecord())
        compose.onAllNodesWithText("M").assertCountEquals(0)
    }

    @Test
    fun aRecordOnANetworkTheRegistryDoesNotListShowsALetterForEachAddress() {
        setSheet(evmRecord(), networks = emptyList())
        compose.onAllNodesWithText("M").assertCountEquals(2)
    }

    @Test
    fun aRequestsOriginIsLabelledAsAClaimThatIsNotVerified() {
        setSheet(evmRecord().copy(origin = "https://app.example"))

        compose.onNodeWithText("Claimed by Request (Not Verified)").assertExists()
        compose.onNodeWithText("https://app.example").assertExists()
        compose.onAllNodesWithText("Origin").assertCountEquals(0)
    }

    @Test
    fun aTapOnTheRecipientClosesTheSheetAndOpensItOnItsChain() {
        val opened = mutableListOf<Pair<String, ChainId>>()

        setSheet(evmRecord(), onOpen = { address, chain -> opened += address to chain })
        compose.onNodeWithText(groupAddress(recipient)).performClick()

        assertEquals(listOf(recipient to EVM_FAMILY), opened)
    }

    @Test
    fun aTapOnABitcoinOutputOpensItsAddressOnBitcoin() {
        val facts = BitcoinRecordFacts(
            scriptType = "Native SegWit",
            outputs = listOf(
                BitcoinRecordOutput(address = bitcoinPaid, amountSats = 30_000, isOwnChange = false),
                BitcoinRecordOutput(address = bitcoinChange, amountSats = 60_000, isOwnChange = true),
            ),
            feeSats = 500,
            feeRateSatPerVb = 2.0,
            notices = emptyList(),
        )
        val opened = mutableListOf<Pair<String, ChainId>>()

        setSheet(record(kind = ReviewKind.BITCOIN_TRANSACTION, networkName = "Bitcoin Testnet4", bitcoin = facts), onOpen = { a, c -> opened += a to c })
        // The change output is an address row too, so it can be opened.
        compose.onNodeWithText(groupAddress(bitcoinChange)).performClick()

        assertEquals(listOf(bitcoinChange to BITCOIN_FAMILY), opened)
    }

    @Test
    fun aTapOnASolanaProgramOpensItOnSolana() {
        val opened = mutableListOf<Pair<String, ChainId>>()
        val rec = record(networkName = "Solana", address = sender, params = listOf("Program" to solanaProgram))

        setSheet(rec, onOpen = { a, c -> opened += a to c })
        compose.onNodeWithText(groupAddress(solanaProgram)).performClick()

        assertEquals(listOf(solanaProgram to SOLANA_FAMILY), opened)
    }

    @Test
    fun aTapOnAnAlgorandCreatorOpensItOnAlgorand() {
        val opened = mutableListOf<Pair<String, ChainId>>()
        val rec = record(networkName = "Algorand Testnet", address = algorandSender, params = listOf("Asset creator" to algorandCreator))

        setSheet(rec, onOpen = { a, c -> opened += a to c })
        compose.onNodeWithText(groupAddress(algorandCreator)).performClick()

        assertEquals(listOf(algorandCreator to ALGORAND_FAMILY), opened)
    }

    @Test
    fun anAccountOfTheVaultIsTheBoldLineAndItsAddressBookNameStillShows() {
        val accounts = mapOf(addressKeyOf(sender) to InternalAccount(displayName = "Savings", autoName = "Seed 0", label = "Cold"))
        val book = listOf(AddressBookEntry(id = "1", address = sender, name = "Alice", label = null))

        setSheet(evmRecord(), book, accounts)

        compose.onNodeWithText("Your account · Savings · Cold").assertIsDisplayed()
        compose.onNodeWithText("Address Name").assertIsDisplayed()
        compose.onNodeWithText("Alice").assertIsDisplayed()
        // The recipient is neither an account of the vault nor in the Address Book.
        compose.onAllNodesWithText("Not in address book").assertCountEquals(1)
    }

    @Test
    fun aBitcoinOutputToAnotherWalletsAccountNamesTheAccountAndItsWallet() {
        val accounts = mapOf(
            addressKeyOf(bitcoinPaid) to InternalAccount(displayName = null, autoName = "Trading 0", label = null, walletName = "Trading"),
        )
        val facts = BitcoinRecordFacts(
            scriptType = "Native SegWit",
            outputs = listOf(BitcoinRecordOutput(address = bitcoinPaid, amountSats = 30_000, isOwnChange = false)),
            feeSats = 500,
            feeRateSatPerVb = 2.0,
            notices = emptyList(),
        )

        setSheet(record(kind = ReviewKind.BITCOIN_TRANSACTION, networkName = "Bitcoin Testnet4", bitcoin = facts), accounts = accounts)

        compose.onNodeWithText("Your account · Trading 0").assertIsDisplayed()
        compose.onNodeWithText("Wallet").assertIsDisplayed()
        compose.onNodeWithText("Trading").assertIsDisplayed()
    }
}
