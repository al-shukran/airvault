package app.lensly.ui.sign

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import app.airvault.core.model.SummaryPart
import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressNotes
import app.lensly.app.InternalAccount
import app.lensly.app.SigningAccount
import app.lensly.app.addressKeyOf
import app.lensly.ui.theme.LumenPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SENDER = "0x71C7656EC7ab88b098defB751B7401B5f6d8976F"
private const val TO_BOOK = "0x9f8F72aA9304c8B593d555F12eF6589cC3A579A2"
private const val TO_ACCOUNT = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
private const val TO_UNKNOWN = "ACXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXMPY"

/** Two colours that differ, so a test can tell which role coloured a name. */
internal val testPalette = LumenPalette(
    accentInk = Color(0xFF111111),
    ring = Color(0xFF111111),
    testnet = Color(0xFF222222),
    positive = Color(0xFF00A050),
    warning = Color(0xFFA05000),
    info = Color(0xFF0050A0),
    chipEdge = Color(0xFF333333),
    glowCore = Color.Transparent,
    glowEdge = Color.Transparent,
)

private fun book(id: String, address: String, name: String) = AddressBookEntry(id, address, name, null)

/** The account that signs, as the review names it: the sender's account name. */
private val signer = SigningAccount(name = "VelvetEcho", walletName = "Wallet 1", address = SENDER)

/** The colour of the span that covers the first [text] of [sentence]: Unspecified when no colour is set on it. */
private fun colourOf(sentence: AnnotatedString, text: String): Color {
    val start = sentence.text.indexOf(text)
    assertTrue("'$text' is in '${sentence.text}'", start >= 0)
    return sentence.spanStyles.lastOrNull { it.start <= start && start < it.end }?.item?.color ?: Color.Unspecified
}

private fun titleOf(send: SummaryPart.Send, notes: Map<String, AddressNotes>, sender: SigningAccount = signer): AnnotatedString =
    summarySentence(listOf(send), notes, sender, FontFamily.Monospace, testPalette)

private fun send(amount: String, asset: String, to: String) = SummaryPart.Send(amount, asset, SENDER, to)

class SendTitleTest {

    @Test
    fun aSenderAndARecipientNamedInTheBookAreAccountBookAndBook() {
        // Case 1: the sender is an account of this device that the Address Book also names; the recipient is an entry only.
        val notes = mapOf(
            addressKeyOf(SENDER) to AddressNotes(InternalAccount(null, "Wallet 1 0", null), book("s", SENDER, "0xElectric")),
            addressKeyOf(TO_BOOK) to AddressNotes(null, book("r", TO_BOOK, "ElectricDusk")),
        )
        val title = titleOf(send("0.4", "ALGO", TO_BOOK), notes)

        assertEquals("Send 0.4 ALGO from VelvetEcho (0xElectric) to ElectricDusk?", title.text)
        assertEquals(testPalette.positive, colourOf(title, "VelvetEcho (0xElectric)"))
        assertEquals(testPalette.info, colourOf(title, "ElectricDusk"))
    }

    @Test
    fun aRecipientInTheBookOnlyIsNamedFromTheBookAndBlue() {
        // Case 2: the sender has no Address Book entry, so it is named by its account alone.
        val notes = mapOf(addressKeyOf(TO_BOOK) to AddressNotes(null, book("r", TO_BOOK, "ElectricDusk")))
        val title = titleOf(send("0.4", "ALGO", TO_BOOK), notes, signer.copy(name = "Wallet 1 0"))

        assertEquals("Send 0.4 ALGO from Wallet 1 0 to ElectricDusk?", title.text)
        assertEquals(testPalette.info, colourOf(title, "ElectricDusk"))
        assertEquals(testPalette.positive, colourOf(title, "Wallet 1 0"))
    }

    @Test
    fun aRecipientThatIsAnAccountOfThisDeviceIsNamedByItAndGreen() {
        // Case 3: the recipient is an internal account that the Address Book does not hold.
        val notes = mapOf(
            addressKeyOf(TO_ACCOUNT) to AddressNotes(InternalAccount(null, "Wallet 1 3", null, walletName = "Wallet 1"), null),
        )
        val title = titleOf(send("0.4", "ALGO", TO_ACCOUNT), notes, signer.copy(name = "Wallet 1 0"))

        assertEquals("Send 0.4 ALGO from Wallet 1 0 to Wallet 1 3?", title.text)
        assertEquals(testPalette.positive, colourOf(title, "Wallet 1 3"))
    }

    @Test
    fun aRecipientThatNeitherNamesShowsItsFullAddressUncoloured() {
        // Case 4: no account and no entry, so the full address is shown in the monospace face and no colour is set.
        val title = titleOf(send("0.4", "ALGO", TO_UNKNOWN), emptyMap(), signer.copy(name = "Wallet 1 0"))

        assertEquals("Send 0.4 ALGO from Wallet 1 0 to $TO_UNKNOWN?", title.text)
        assertEquals(Color.Unspecified, colourOf(title, TO_UNKNOWN))
    }

    @Test
    fun aRecipientThatIsAnAccountAndAnEntryIsNamedAccountBookAndGreen() {
        val notes = mapOf(
            addressKeyOf(TO_ACCOUNT) to AddressNotes(
                InternalAccount(null, "Wallet 1 3", null, walletName = "Wallet 1"),
                book("r", TO_ACCOUNT, "Carol"),
            ),
        )
        val title = titleOf(send("1", "MON", TO_ACCOUNT), notes, signer.copy(name = "Wallet 1 0"))

        assertEquals("Send 1 MON from Wallet 1 0 to Wallet 1 3 (Carol)?", title.text)
        assertEquals(testPalette.positive, colourOf(title, "Wallet 1 3 (Carol)"))
    }

    @Test
    fun theSigningAccountIsNamedAsTheReviewNamesItEvenWithoutANote() {
        val title = titleOf(send("0.4", "ALGO", TO_UNKNOWN), emptyMap())

        assertEquals("Send 0.4 ALGO from VelvetEcho to $TO_UNKNOWN?", title.text)
        assertEquals(testPalette.positive, colourOf(title, "VelvetEcho"))
    }

    @Test
    fun aCounterpartyInAnotherSentenceKeepsItsTextAndTakesItsRoleColour() {
        val notes = mapOf(
            addressKeyOf(TO_BOOK) to AddressNotes(null, book("r", TO_BOOK, "ElectricDusk")),
            addressKeyOf(TO_ACCOUNT) to AddressNotes(InternalAccount(null, "Wallet 1 3", null), null),
        )
        val parts = listOf(
            SummaryPart.Text("Calls a contract on "),
            SummaryPart.Counterparty(TO_BOOK),
            SummaryPart.Text(" and "),
            SummaryPart.Counterparty(TO_ACCOUNT),
            SummaryPart.Text("."),
        )
        val sentence = summarySentence(parts, notes, signer, FontFamily.Monospace, testPalette)

        assertEquals("Calls a contract on ElectricDusk and Wallet 1 3.", sentence.text)
        assertEquals(testPalette.info, colourOf(sentence, "ElectricDusk"))
        assertEquals(testPalette.positive, colourOf(sentence, "Wallet 1 3"))
    }

    @Test
    fun nameRoleIsGreenForAnAccountOfThisDeviceAndBlueForAnEntryOnly() {
        val account = InternalAccount(null, "Wallet 1 3", null)
        val entry = book("r", TO_BOOK, "ElectricDusk")

        assertNull(nameRoleOf(null))
        assertEquals(NameRole.ACCOUNT, nameRoleOf(AddressNotes(account, null)))
        assertEquals(NameRole.BOOK, nameRoleOf(AddressNotes(null, entry)))
        assertEquals(NameRole.ACCOUNT, nameRoleOf(AddressNotes(account, entry)))
    }

    @Test
    fun eachRoleTakesItsThemeColour() {
        assertEquals(testPalette.positive, NameRole.ACCOUNT.colourIn(testPalette))
        assertEquals(testPalette.info, NameRole.BOOK.colourIn(testPalette))
    }

    @Test
    fun aPartyWithAccountAndBookNamesIsAccountBook() {
        assertEquals(PartyName("Savings (Bob)", NameRole.ACCOUNT), partyNameOf("Savings", "Bob"))
        assertEquals(PartyName("Savings", NameRole.ACCOUNT), partyNameOf("Savings", null))
        assertEquals(PartyName("Bob", NameRole.BOOK), partyNameOf(null, "Bob"))
        assertNull(partyNameOf(null, null))
    }

    @Test
    fun aSendNamesTheSignerAndOnlyTheAddressesItsNotesHold() {
        assertEquals(PartyName("VelvetEcho", NameRole.ACCOUNT), sendPartyOf(SENDER, emptyMap(), signer))
        assertNull(sendPartyOf(TO_BOOK, emptyMap(), signer))
    }
}
