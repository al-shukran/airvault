package app.lensly.ui.screenshot

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressNotes
import app.lensly.app.InternalAccount
import app.lensly.app.addressKeyOf
import app.lensly.ui.settings.AddressBookFormScreen
import app.lensly.ui.settings.AddressBookScreen
import app.lensly.ui.settings.AddressBookField
import app.lensly.ui.sign.TxReviewScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val CAROL_ADDRESS = "0x52908400098527886E0F7030069857D2E4169EE7"

private val listEntries = listOf(
    AddressBookEntry("entry-1", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "Alice", "Exchange deposit"),
    AddressBookEntry("entry-2", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", "Bob", null),
    AddressBookEntry("entry-3", CAROL_ADDRESS, "Carol", "Cold storage"),
)

/** The Address Book entry of the review's recipient, for the To row's Address Name and Address Label. */
private val recipientEntry = AddressBookEntry("entry-recipient", sampleNativeReview.toAddress, "Exchange deposit", "Coinbase")

/** The device account of the review's recipient, for the To row's Internal Account cue. */
private val recipientAccount = InternalAccount(displayName = "Savings", autoName = "Main wallet 1", label = "Cold storage")

/** The sample native transfer, with the notes of its recipient. Each To row case is one of these. */
private fun reviewWith(notes: AddressNotes?) = sampleNativeReview.copy(
    addressNotes = if (notes == null) emptyMap() else mapOf(addressKeyOf(sampleNativeReview.toAddress) to notes),
)

/** The frames a scroll gets to settle before the capture, as the sign review screenshots give it. */
private const val SETTLE_FRAMES = 6

/**
 * Opens the Transaction details, where the To row and its notes are, then scrolls the review until the first row named [label] is on
 * screen. The clock runs while the scroll does, as the sign review screenshots do, then it stops and the frames settle. Without the
 * running clock the scroll never completes.
 */
private fun scrollReviewTo(label: String): ComposeContentTestRule.() -> Unit = {
    mainClock.autoAdvance = true
    onNodeWithText("Transaction").performScrollTo().performClick()
    waitForIdle()
    onAllNodesWithText(label, ignoreCase = true).onFirst().performScrollTo()
    waitForIdle()
    mainClock.autoAdvance = false
    repeat(SETTLE_FRAMES) {
        waitForIdle()
        mainClock.advanceTimeByFrame()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddressBookScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun addressBookList() = compose.snapshot("address_book_list") {
        AddressBookScreen(entries = listEntries, onOpen = {}, onAdd = {}, onBack = {})
    }

    @Test
    fun addressBookFormAdd() = compose.snapshot("address_book_form_add") {
        AddressBookFormScreen(
            title = "Add address",
            address = CAROL_ADDRESS,
            name = "Carol",
            label = "",
            focused = AddressBookField.LABEL,
            onFocus = {},
            onKey = {},
            onBackspace = {},
            onSave = {},
            onBack = {},
        )
    }

    @Test
    fun addressBookFormEdit() = compose.snapshot("address_book_form_edit") {
        AddressBookFormScreen(
            title = "Edit address",
            address = CAROL_ADDRESS,
            name = "Carol",
            label = "Cold storage",
            focused = null,
            onFocus = {},
            onKey = {},
            onBackspace = {},
            onSave = {},
            onBack = {},
            onDelete = {},
        )
    }

    @Test
    fun reviewToNoNotes() = compose.snapshot("tx_review_to_none") {
        TxReviewScreen(review = reviewWith(null), onApprove = {}, onReject = {})
    }

    // The notes sit in the Transaction details, so each capture opens them and scrolls to the last row of its To block first.
    @Test
    fun reviewToAddressBookEntry() = compose.snapshot("tx_review_to_book", beforeCapture = scrollReviewTo(recipientEntry.label.orEmpty())) {
        TxReviewScreen(review = reviewWith(AddressNotes(internal = null, entry = recipientEntry)), onApprove = {}, onReject = {})
    }

    @Test
    fun reviewToInternalAccount() = compose.snapshot("tx_review_to_internal", beforeCapture = scrollReviewTo(recipientAccount.label.orEmpty())) {
        TxReviewScreen(review = reviewWith(AddressNotes(internal = recipientAccount, entry = null)), onApprove = {}, onReject = {})
    }

    @Test
    fun reviewToInternalAccountAndEntry() = compose.snapshot("tx_review_to_both", beforeCapture = scrollReviewTo(recipientEntry.label.orEmpty())) {
        TxReviewScreen(
            review = reviewWith(AddressNotes(internal = recipientAccount, entry = recipientEntry)),
            onApprove = {},
            onReject = {},
        )
    }
}
