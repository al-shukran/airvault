package app.lensly.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressNotes
import app.lensly.app.InternalAccount
import app.lensly.app.addressKeyOf
import app.lensly.ui.settings.AddressBookField
import app.lensly.ui.settings.AddressBookFormScreen
import app.lensly.ui.settings.AddressBookScreen
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
private val recipientEntry = AddressBookEntry("entry-recipient", SAMPLE_TO, "Exchange deposit", "Coinbase")

/** The device account of the review's recipient, for the To row's Internal Account cue. */
private val recipientAccount = InternalAccount(displayName = "Savings", autoName = "Main wallet 1", label = "Cold storage")

/** The notes of the sample review's recipient, keyed by its address, or none when [notes] is null. Each To row case is one of these. */
private fun notesOfRecipient(notes: AddressNotes?): Map<String, AddressNotes> =
    if (notes == null) emptyMap() else mapOf(addressKeyOf(SAMPLE_TO) to notes)

/** The frames a scroll gets to settle before the capture, as the sign review screenshots give it. */
private const val SETTLE_FRAMES = 6

/**
 * Scrolls the review until the first row named [label] is on screen. The clock runs while the scroll does, as the sign review
 * screenshots do, then it stops and the frames settle. Without the running clock the scroll never completes.
 */
private fun scrollReviewTo(label: String): ComposeContentTestRule.() -> Unit = {
    mainClock.autoAdvance = true
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
    fun addressBookList() = compose.snapshot("address_book_list") { ListSnapshot() }

    @Test
    @Config(qualifiers = SCREENSHOT_NIGHT_QUALIFIERS)
    fun addressBookListNight() = compose.snapshot("address_book_list_night") { ListSnapshot() }

    @Test
    fun addressBookFormAdd() = compose.snapshot("address_book_form_add") { FormAddSnapshot() }

    @Test
    @Config(qualifiers = SCREENSHOT_NIGHT_QUALIFIERS)
    fun addressBookFormAddNight() = compose.snapshot("address_book_form_add_night") { FormAddSnapshot() }

    @Test
    fun addressBookFormEdit() = compose.snapshot("address_book_form_edit") { FormEditSnapshot() }

    @Test
    @Config(qualifiers = SCREENSHOT_NIGHT_QUALIFIERS)
    fun addressBookFormEditNight() = compose.snapshot("address_book_form_edit_night") { FormEditSnapshot() }

    @Composable
    private fun ListSnapshot() {
        AddressBookScreen(entries = listEntries, onOpen = {}, onAdd = {}, onBack = {})
    }

    @Composable
    private fun FormAddSnapshot() {
        AddressBookFormScreen(
            title = "Add Address",
            address = CAROL_ADDRESS,
            name = "Carol",
            label = "",
            notes = "",
            focused = AddressBookField.LABEL,
            onFocus = {},
            onKey = {},
            onBackspace = {},
            onSave = {},
            onBack = {},
        )
    }

    @Composable
    private fun FormEditSnapshot() {
        AddressBookFormScreen(
            title = "Edit Address",
            address = CAROL_ADDRESS,
            name = "Carol",
            label = "Cold storage",
            notes = "Hardware wallet, seed kept offline",
            focused = null,
            addressFixed = true,
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
        EvmReviewScreen(review = sampleNativeReview)
    }

    // The notes sit under the To row, so each capture scrolls to the last row of its To block first.
    @Test
    fun reviewToAddressBookEntry() = compose.snapshot("tx_review_to_book", beforeCapture = scrollReviewTo(recipientEntry.label.orEmpty())) {
        EvmReviewScreen(review = sampleNativeReview, notes = notesOfRecipient(AddressNotes(internal = null, entry = recipientEntry)))
    }

    @Test
    fun reviewToInternalAccount() = compose.snapshot(
        "tx_review_to_internal",
        beforeCapture = scrollReviewTo("Your account · Savings · ${recipientAccount.label}"),
    ) {
        EvmReviewScreen(review = sampleNativeReview, notes = notesOfRecipient(AddressNotes(internal = recipientAccount, entry = null)))
    }

    @Test
    fun reviewToInternalAccountAndEntry() = compose.snapshot("tx_review_to_both", beforeCapture = scrollReviewTo(recipientEntry.label.orEmpty())) {
        EvmReviewScreen(
            review = sampleNativeReview,
            notes = notesOfRecipient(AddressNotes(internal = recipientAccount, entry = recipientEntry)),
        )
    }
}
