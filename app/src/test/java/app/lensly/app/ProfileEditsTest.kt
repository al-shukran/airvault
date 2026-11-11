package app.lensly.app

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** The rules behind the profile editors and the batch style editor, checked without any screen. */
class ProfileEditsTest {

    private val generated = generatedIdentity(5)

    private fun account(focus: ProfileField = ProfileField.NAME) = AccountDraft(
        name = "",
        colour = generated.colour,
        icon = generated.icon,
        label = "",
        group = "",
        focus = focus,
    )

    @Test
    fun typingGoesToTheFocusedFieldAndStopsAtItsLength() {
        val typed = (1..40).fold(account(ProfileField.NAME)) { draft, _ -> draft.typing('n') }
        assertEquals(DISPLAY_NAME_MAX_LENGTH, typed.name.length)
        assertEquals("", typed.label)

        val label = (1..20).fold(account(ProfileField.LABEL)) { draft, _ -> draft.typing('l') }
        assertEquals(PROFILE_TEXT_MAX_LENGTH, label.label.length)
        assertEquals("", label.name)
    }

    @Test
    fun backspaceRemovesFromTheFocusedField() {
        val draft = account(ProfileField.GROUP).typing('H').typing('o').backspace()

        assertEquals("H", draft.group)
        assertEquals("", draft.name)
    }

    @Test
    fun aBlankNameLabelOrGroupSavesAsTheDefault() {
        val draft = account().copy(name = "   ", label = "  ", group = "")

        assertEquals(AccountProfile(), draft.toProfile(generated))
    }

    @Test
    fun theSavedProfileStoresOnlyWhatDiffersFromTheGeneratedIdentity() {
        val other = ProfileColour.entries.first { it != generated.colour }
        val draft = account().copy(name = " Savings ", colour = other, icon = generated.icon, label = "Rent ", group = "Home")

        assertEquals(
            AccountProfile(name = "Savings", colour = other, icon = null, label = "Rent", group = "Home"),
            draft.toProfile(generated),
        )
    }

    @Test
    fun resettingTheStyleKeepsTheNameAndReturnsTheGeneratedIdentity() {
        val draft = account().copy(name = "Main", colour = ProfileColour.PINK, label = "Rent", group = "Home")

        val reset = draft.resetStyle(generated)

        assertEquals("Main", reset.name)
        assertEquals(AccountProfile(name = "Main"), reset.toProfile(generated))
    }

    @Test
    fun aDraftStartsFromTheStoredProfileAndTheGeneratedIdentityForTheRest() {
        val draft = AccountDraft.of(AccountProfile(name = "Main", label = "Rent"), generated)

        assertEquals("Main", draft.name)
        assertEquals("Rent", draft.label)
        assertEquals(generated.colour, draft.colour)
        assertEquals(generated.icon, draft.icon)
    }

    @Test
    fun aWalletNameThatIsBlankKeepsTheCurrentOne() {
        val draft = WalletProfileDraft(name = "   ", colour = generated.colour, icon = generated.icon, label = "", groups = emptyList())

        assertEquals("Seed", draft.nameToSave("Seed"))
        assertEquals("Renamed", draft.copy(name = " Renamed ").nameToSave("Seed"))
    }

    @Test
    fun aWalletGroupTypedInIsAddedOnceAndNeverPastTheCap() {
        val full = List(WALLET_MAX_GROUPS - 1) { "g$it" }
        val draft = WalletProfileDraft(
            name = "Seed",
            colour = generated.colour,
            icon = generated.icon,
            label = "",
            groups = full,
            focus = ProfileField.GROUP,
        ).typing('H').typing('o').typing('m').typing('e')

        assertEquals(full + "Home", draft.groupsToSave())
        assertEquals(WALLET_MAX_GROUPS, draft.groupsToSave().size)

        val capped = draft.copy(groups = draft.groupsToSave()).copy(pendingGroup = "").typing('x')
        assertEquals("", capped.pendingGroup)
        assertEquals(List(WALLET_MAX_GROUPS) { if (it < WALLET_MAX_GROUPS - 1) "g$it" else "Home" }, capped.groups)
    }

    @Test
    fun aWalletGroupAlreadyOnTheWalletIsNotRepeated() {
        val draft = WalletProfileDraft(
            name = "Seed",
            colour = generated.colour,
            icon = generated.icon,
            label = "",
            groups = listOf("Work"),
            pendingGroup = " Work ",
        )

        assertEquals(listOf("Work"), draft.groupsToSave())
    }

    @Test
    fun aWalletProfileSavesWhatDiffersFromTheGeneratedIdentity() {
        val draft = WalletProfileDraft(
            name = "Seed",
            colour = generated.colour,
            icon = ProfileIcon.HEART,
            label = "Everyday ",
            groups = listOf("Family"),
        )

        assertEquals(WalletProfile(icon = ProfileIcon.HEART, label = "Everyday", groups = listOf("Family")), draft.toProfile(generated))
    }

    @Test
    fun pickingTheSameColourAgainUnPicksItSoABatchCanLeaveItAlone() {
        val picked = StyleEdits().pickColour(ProfileColour.BLUE)

        assertEquals(ProfileColour.BLUE, picked.colour)
        assertNull(picked.pickColour(ProfileColour.BLUE).colour)
        assertEquals(ProfileColour.RED, picked.pickColour(ProfileColour.RED).colour)
    }

    @Test
    fun aBatchChangesApplyInOrderAndBlankTextChangesNothing() {
        val edits = StyleEdits(
            colour = ProfileColour.TEAL,
            icon = ProfileIcon.BELL,
            label = TextEdit.Set("  "),
            group = TextEdit.Set("Home"),
        )

        assertEquals(
            listOf(
                StyleChange.Colour(ProfileColour.TEAL),
                StyleChange.Icon(ProfileIcon.BELL),
                StyleChange.Group("Home"),
            ),
            edits.changes(),
        )
    }

    @Test
    fun aBatchClearsWhatItIsAskedToClear() {
        val edits = StyleEdits().clear(StyleField.LABEL).clear(StyleField.GROUP)

        assertEquals(listOf(StyleChange.Label(null), StyleChange.Group(null)), edits.changes())
    }

    @Test
    fun aBatchWithNothingSetHasNoChanges() {
        assertEquals(emptyList(), StyleEdits().changes())
        // Typing and then deleting the same letter leaves the label blank, which changes nothing.
        assertEquals(emptyList(), StyleEdits(focus = StyleField.LABEL).typing('x').backspace().changes())
    }

    @Test
    fun keysTypedWhileNoFieldHasFocusChangeNothing() {
        val account = account().copy(focus = null)
        assertEquals(account, account.typing('x'))
        assertEquals(account, account.backspace())

        val wallet = WalletProfileDraft(name = "Seed", colour = generated.colour, icon = generated.icon, label = "", groups = emptyList())
        assertEquals(wallet, wallet.typing('x'))
        assertEquals(wallet, wallet.backspace())

        val batch = StyleEdits()
        assertEquals(batch, batch.typing('x'))
        assertEquals(batch, batch.backspace())
    }

    @Test
    fun typingIntoTheBatchLabelBuildsItsTextUpToTheLength() {
        val edits = (1..20).fold(StyleEdits(focus = StyleField.LABEL)) { acc, _ -> acc.typing('r') }

        assertEquals(TextEdit.Set("r".repeat(PROFILE_TEXT_MAX_LENGTH)), edits.label)
        assertEquals(TextEdit.Keep, edits.group)
    }
}
