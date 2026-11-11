package app.lensly.app

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** The Visual Identity rules, the palette and icon codes, the label rules, and the records of profiles, read and written. */
class ProfilesTest {

    /** A version 2 account record written by hand: the version, then [flags], then whatever [body] writes. */
    private fun accountRecord(flags: Int, body: DataOutputStream.() -> Unit = {}): ByteArray =
        record(version = 2, flags = flags, body = body)

    /** A version 1 wallet profile record written by hand: the version, then [flags], then whatever [body] writes. */
    private fun walletRecord(flags: Int, body: DataOutputStream.() -> Unit = {}): ByteArray =
        record(version = 1, flags = flags, body = body)

    private fun record(version: Int, flags: Int, body: DataOutputStream.() -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(version)
            out.writeByte(flags)
            out.body()
        }
        return buffer.toByteArray()
    }

    @Test
    fun theFirstNinetySixPositionsGetDifferentIdentities() {
        assertEquals(96, (0 until 96).map { generatedIdentity(it) }.toSet().size)
    }

    @Test
    fun fiftyEntriesLookDifferentAtAGlance() {
        assertEquals(50, (0 until 50).map { generatedIdentity(it) }.toSet().size)
    }

    @Test
    fun aGeneratedIdentityStartsAtTheFirstColourAndIconAndAdvancesAfterACycleOfColours() {
        assertEquals(VisualIdentity(ProfileColour.RED, ProfileIcon.STAR), generatedIdentity(0))
        assertEquals(
            VisualIdentity(ProfileColour.RED, ProfileIcon.HEART),
            generatedIdentity(ProfileColour.entries.size),
        )
    }

    @Test
    fun aNegativePositionHasNoIdentity() {
        assertFailsWith<IllegalArgumentException> { generatedIdentity(-1) }
    }

    @Test
    fun aColourOrIconThatIsSetIsKeptAndTheOtherIsGenerated() {
        val generated = generatedIdentity(5)

        assertEquals(VisualIdentity(ProfileColour.TEAL, generated.icon), visualIdentity(ProfileColour.TEAL, null, 5))
        assertEquals(VisualIdentity(generated.colour, ProfileIcon.BELL), visualIdentity(null, ProfileIcon.BELL, 5))
        assertEquals(generated, visualIdentity(null, null, 5))
    }

    @Test
    fun thePaletteAndTheIconSetHaveTheirSizesAndUniqueCodes() {
        assertEquals(16, ProfileColour.entries.size)
        assertEquals(60, ProfileIcon.entries.size)
        assertEquals(ProfileColour.entries.size, ProfileColour.entries.map { it.wire }.toSet().size)
        assertEquals(ProfileIcon.entries.size, ProfileIcon.entries.map { it.wire }.toSet().size)
        assertEquals(ProfileColour.BLUE, ProfileColour.fromWire(ProfileColour.BLUE.wire))
        assertEquals(ProfileIcon.CALENDAR, ProfileIcon.fromWire(ProfileIcon.CALENDAR.wire))
    }

    @Test
    fun aCodeThisBuildDoesNotKnowHasNoColourOrIcon() {
        assertNull(ProfileColour.fromWire(0))
        assertNull(ProfileColour.fromWire(99))
        assertNull(ProfileIcon.fromWire(0))
        assertNull(ProfileIcon.fromWire(99))
    }

    @Test
    fun aLabelIsTrimmedAndAtMostSixteenCharacters() {
        assertEquals("Rent", profileTextOf("  Rent  "))
        assertEquals("x".repeat(PROFILE_TEXT_MAX_LENGTH), profileTextOf("x".repeat(PROFILE_TEXT_MAX_LENGTH)))
        assertFailsWith<IllegalArgumentException> { profileTextOf("x".repeat(PROFILE_TEXT_MAX_LENGTH + 1)) }
        assertFailsWith<IllegalArgumentException> { profileTextOf("   ") }
        assertFailsWith<IllegalArgumentException> { profileTextOf("a\nb") }
        assertTrue(isValidProfileText("Holiday"))
        assertFalse(isValidProfileText(""))
    }

    @Test
    fun anAccountProfileRoundTripsEveryField() {
        val profile = AccountProfile(
            name = "Savings",
            colour = ProfileColour.VIOLET,
            icon = ProfileIcon.CART,
            label = "Quarterly rent",
            group = "Home",
        )

        assertEquals(profile, WalletRecords.decodeAccountProfile(WalletRecords.encodeAccountProfile(profile)))
    }

    @Test
    fun aPartlyFilledAccountProfileRoundTripsWithItsDefaults() {
        val styled = AccountProfile(colour = ProfileColour.PINK, group = "Work")
        val named = AccountProfile(name = "Main")
        val empty = AccountProfile()

        assertEquals(styled, WalletRecords.decodeAccountProfile(WalletRecords.encodeAccountProfile(styled)))
        assertEquals(named, WalletRecords.decodeAccountProfile(WalletRecords.encodeAccountProfile(named)))
        assertEquals(empty, WalletRecords.decodeAccountProfile(WalletRecords.encodeAccountProfile(empty)))
    }

    @Test
    fun aVersionOneNameRecordReadsAsANameWithNoStyle() {
        val legacy = LegacyWalletRecords.accountName("Savings")

        assertEquals(AccountProfile(name = "Savings"), WalletRecords.decodeAccountProfile(legacy))
    }

    @Test
    fun aColourOrIconCodeThisBuildDoesNotKnowReadsAsTheDefault() {
        // The colour and icon flags, then two codes that are not in the palette or the icon set.
        val bytes = accountRecord(flags = 2 or 4) {
            writeByte(99)
            writeByte(99)
        }

        assertEquals(AccountProfile(), WalletRecords.decodeAccountProfile(bytes))
    }

    @Test
    fun aRecordOfAnUnknownVersionOrWithAnUnknownFieldIsRefused() {
        assertFailsWith<IllegalArgumentException> { WalletRecords.decodeAccountProfile(byteArrayOf(9)) }
        assertFailsWith<IllegalArgumentException> { WalletRecords.decodeAccountProfile(accountRecord(flags = 32)) }
        assertFailsWith<IllegalArgumentException> { WalletRecords.decodeWalletProfile(walletRecord(flags = 8 shl 4)) }
    }

    @Test
    fun aWalletProfileRoundTripsEveryField() {
        val profile = WalletProfile(
            colour = ProfileColour.TEAL,
            icon = ProfileIcon.HOME,
            label = "Everyday",
            groups = listOf("Work", "Family"),
        )

        assertEquals(profile, WalletRecords.decodeWalletProfile(WalletRecords.encodeWalletProfile(profile)))
    }

    @Test
    fun aWalletWithOnlyGroupsRoundTripsAndAnEmptyOneDoes() {
        val groups = WalletProfile(groups = listOf("Work"))

        assertEquals(groups, WalletRecords.decodeWalletProfile(WalletRecords.encodeWalletProfile(groups)))
        assertEquals(
            WalletProfile(),
            WalletRecords.decodeWalletProfile(WalletRecords.encodeWalletProfile(WalletProfile())),
        )
    }

    @Test
    fun aWalletProfileWithTooManyGroupsIsRefusedWhenWrittenAndWhenRead() {
        val tooMany = List(WALLET_MAX_GROUPS + 1) { "g$it" }

        assertFailsWith<IllegalArgumentException> { WalletRecords.encodeWalletProfile(WalletProfile(groups = tooMany)) }
        val stored = walletRecord(flags = 0) {
            writeByte(WALLET_MAX_GROUPS + 1)
            tooMany.forEach { writeUTF(it) }
        }
        assertFailsWith<IllegalArgumentException> { WalletRecords.decodeWalletProfile(stored) }
    }
}
