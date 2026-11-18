package app.lumio.ui.send

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.lensly.app.AddressBookEntry
import app.lensly.app.InternalAccount
import app.lensly.app.addressKeyOf
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.HdWallet
import app.lensly.ui.theme.LumenTheme
import app.lumio.evm.EvmChain
import app.lumio.evm.PreparedSend
import app.lumio.pair.ADDRESS_BOOK_ALICE
import app.lumio.pair.ADDRESS_BOOK_COLD
import app.lumio.pair.PairFixtures
import java.math.BigInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The review of an Ethereum send names its To address from the Address Book: the saved name shows, and an address the book does not hold has none. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SendReviewAddressBookTest {

    @get:Rule
    val compose = createComposeRule()

    private val chain = EvmChain(1L, "Ethereum", "ETH", 18, false, "https://etherscan.io")
    private val from = AccountAddresses.addressAt(HdWallet(PairFixtures.seed).accountXpub(), 0)
    private val ownSavings = InternalAccount(displayName = "Savings", autoName = "Vault 0", label = "Cold")
    private val ownVault1 = InternalAccount(displayName = null, autoName = "Vault 1", label = null)

    @Test
    fun theReviewShowsTheSavedNameNextToTheToAddress() {
        setReview(send(to = ADDRESS_BOOK_ALICE.address), listOf(ADDRESS_BOOK_ALICE))

        compose.onNodeWithText("Alice").assertExists()
    }

    @Test
    fun theReviewShowsNoNameForAnAddressTheBookDoesNotHold() {
        setReview(send(to = ADDRESS_BOOK_ALICE.address), listOf(ADDRESS_BOOK_COLD))

        compose.onAllNodesWithText("Alice").assertCountEquals(0)
    }

    @Test
    fun theReviewNamesTheSendingAccountAsYourAccount() {
        setReview(send(to = ADDRESS_BOOK_ALICE.address), emptyList(), accounts = mapOf(addressKeyOf(from) to ownSavings))

        compose.onNodeWithText("Your account · Savings · Cold").assertExists()
    }

    @Test
    fun anAddressThatIsAnAccountAndAnEntryShowsBoth() {
        val to = ADDRESS_BOOK_ALICE.address
        setReview(send(to = to), listOf(ADDRESS_BOOK_ALICE), accounts = mapOf(addressKeyOf(to) to ownVault1))

        compose.onNodeWithText("Your account · Vault 1").assertExists()
        compose.onNodeWithText("Address Name").assertExists()
        compose.onNodeWithText("Alice").assertExists()
    }

    private fun setReview(send: PreparedSend, book: List<AddressBookEntry>, accounts: Map<String, InternalAccount> = emptyMap()) {
        compose.setContent {
            LumenTheme {
                SendReviewScreen(
                    send = send,
                    suggested = send,
                    notice = null,
                    onSave = {},
                    onConfirm = {},
                    onBack = {},
                    addressBook = book,
                    accounts = accounts,
                )
            }
        }
    }

    private fun send(to: String) = PreparedSend(
        chain = chain,
        index = 0,
        from = from,
        to = to,
        valueWei = BigInteger.TEN.pow(16),
        nonce = 7,
        gasLimit = BigInteger.valueOf(21_000),
        maxFeePerGas = BigInteger.valueOf(30_000_000_000L),
        maxPriorityFeePerGas = BigInteger.valueOf(1_500_000_000L),
        balanceWei = BigInteger.TEN.pow(18),
    )
}
