package app.lumio.ui.transactions

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lensly.app.AddressBookEntry
import app.lensly.ui.theme.ClearAirTheme
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.pair.PairedAccount
import app.lumio.pair.PairedWallet
import app.lumio.transactions.TxDetail
import app.lumio.ui.kit.ADDRESS_LINE_TAG
import app.lumio.ui.kit.spokenAddress
import app.lumio.ui.kit.spokenHash
import java.math.BigInteger
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Every address on a transaction page is a tappable row that opens its address-book sheet, and an address that the wallet's Address Book names shows
 * that name. The page is read only, so no row edits an entry.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransactionDetailAddressTest {
    @get:Rule
    val compose = createComposeRule()

    private val network = NetworkId(Networks.evm(1), testnet = false)
    private val account = "0x" + "a1".repeat(20)
    private val friend = "0x" + "5c".repeat(20)

    private fun detail(): TxDetail = TxDetail(
        tx = LumioTx(
            id = "tx-1",
            network = network,
            account = account,
            hash = "01".repeat(32),
            timestampMs = 1_700_000_000_000L,
            asset = TxAsset(contract = null, symbol = "ETH", name = "Ether", decimals = 18),
            amount = BigInteger.TEN.pow(17),
            direction = TxDirection.OUT,
            fee = null,
            counterparty = friend,
            status = TxStatus.CONFIRMED,
            confirmations = 120L,
            inputData = null,
            memo = null,
        ),
        usd = null,
        comment = null,
        chain = "Ethereum",
        feeText = null,
        explorerUrl = null,
        flags = emptyList(),
    )

    @Test
    fun everyAddressOnThePageOpensItsSheet() {
        val tapped = mutableListOf<String>()
        compose.setContent {
            ClearAirTheme {
                TransactionDetailScreen(
                    detail = detail(),
                    accountNames = mapOf(account to "PulsarNova #5"),
                    book = emptyList(),
                    backLabel = "Activity",
                    showBalance = true,
                    onAddress = { tapped += it },
                    onExplorer = {},
                    onBack = {},
                    onLock = {},
                )
            }
        }

        compose.onNodeWithText("Not in Address Book").performClick()
        compose.onNodeWithText("PulsarNova #5").performClick()

        assertEquals(listOf(friend, account), tapped)
    }

    @Test
    fun aCounterpartyInTheAddressBookIsNamed() {
        val book = listOf(AddressBookEntry(id = "entry-1", address = friend, name = "Lena Park", label = null, group = "Friends"))
        compose.setContent {
            ClearAirTheme {
                TransactionDetailScreen(
                    detail = detail(),
                    accountNames = emptyMap(),
                    book = book,
                    backLabel = "Activity",
                    showBalance = true,
                    onAddress = {},
                    onExplorer = {},
                    onBack = {},
                    onLock = {},
                )
            }
        }

        compose.onNodeWithText("Lena Park").assertIsDisplayed()
        compose.onNodeWithText("Address book · Group Friends").assertIsDisplayed()
    }

    @Test
    fun aCounterpartyNameWithHiddenCharactersIsShownAsPlainTextAboveItsFullAddress() {
        val book = listOf(AddressBookEntry(id = "entry-1", address = friend, name = "Lena\u202E Park\u200B", label = null))
        compose.setContent {
            ClearAirTheme {
                TransactionDetailScreen(
                    detail = detail(),
                    accountNames = emptyMap(),
                    book = book,
                    backLabel = "Activity",
                    showBalance = true,
                    onAddress = {},
                    onExplorer = {},
                    onBack = {},
                    onLock = {},
                )
            }
        }

        compose.onNodeWithText("Lena Park").assertIsDisplayed()
        compose.onNodeWithText("0x5c5c 5c5c 5c5c 5c5c 5c5c 5c5c 5c5c 5c5c 5c5c 5c5c").assertIsDisplayed()
    }

    private fun show(names: Map<String, String>, face: AccountFace = AccountFace.UNKNOWN) {
        compose.setContent {
            ClearAirTheme {
                TransactionDetailScreen(
                    detail = detail(),
                    accountNames = names,
                    book = emptyList(),
                    backLabel = "Activity",
                    showBalance = true,
                    onAddress = {},
                    onExplorer = {},
                    onBack = {},
                    onLock = {},
                    unnamedAccount = face,
                )
            }
        }
    }

    @Test
    fun theOwnAccountSaysItIsYoursAndShowsItsAddressOnALineOfItsOwn() {
        show(mapOf(account to "PulsarNova #5"))

        compose.onNodeWithText("PulsarNova #5").assertIsDisplayed()
        compose.onNodeWithText("Your account").assertIsDisplayed()
        val spoken = compose.onAllNodesWithTag(ADDRESS_LINE_TAG, useUnmergedTree = true)[0].fetchSemanticsNode().config[SemanticsProperties.Text].first().text
        assertEquals(spokenAddress(account).text, spoken)
    }

    @Test
    fun anUnnamedOwnAccountIsItsPlaceAndItsMonogramNotItsCutAddress() {
        show(emptyMap(), AccountFace(title = "Account 1", monogram = "E1"))

        compose.onNodeWithText("Account 1").assertIsDisplayed()
        compose.onNodeWithText("E1").assertIsDisplayed()
    }

    @Test
    fun anOwnAccountTheWalletDoesNotHoldIsAccountWithAQuestionMark() {
        show(emptyMap())

        compose.onNodeWithText("Account", substring = false, useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("?", useUnmergedTree = true).assertCountEquals(2) // the counterparty is not in the book either
    }

    @Test
    fun theHashIsInGroupsOfFourAndSpokenVerbatim() {
        show(emptyMap())

        val hash = detail().tx.hash
        compose.onNodeWithText(spokenHash(hash).text, useUnmergedTree = true).assertExists()
    }

    private fun walletOf(vararg accounts: PairedAccount) = PairedWallet(
        id = "w", name = "Vault", fingerprint = 1L, createdAtMs = 1L, bitcoin = emptyList(), evmAccounts = accounts.toList(),
    )

    @Test
    fun theFaceOfAnUnnamedAccountIsItsPlaceInTheWalletAndItsChainsLetter() {
        val wallet = walletOf(PairedAccount(0, "0x" + "b2".repeat(20), null, null, null), PairedAccount(1, account, null, null, null))

        assertEquals(AccountFace(title = "Account 2", monogram = "E2"), unnamedFaceOf(wallet, account))
        assertEquals(AccountFace.UNKNOWN, unnamedFaceOf(wallet, friend))
        assertEquals(AccountFace.UNKNOWN, unnamedFaceOf(null, account))
    }
}
