package app.lensly.app

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DisplayNameTest {

    @Test
    fun theLimitIsThirtyTwoCharacters() {
        assertEquals(32, DISPLAY_NAME_MAX_LENGTH)
    }

    @Test
    fun aNameOfOneToThirtyTwoCharactersIsValid() {
        assertTrue(isValidDisplayName("A"))
        assertTrue(isValidDisplayName("x".repeat(DISPLAY_NAME_MAX_LENGTH)))
    }

    @Test
    fun anEmptyNameAndAnOverlongNameAreNotValid() {
        assertFalse(isValidDisplayName(""))
        assertFalse(isValidDisplayName("x".repeat(DISPLAY_NAME_MAX_LENGTH + 1)))
    }

    @Test
    fun aControlCharacterIsNotValid() {
        assertFalse(isValidDisplayName("a\nb"))
        assertFalse(isValidDisplayName("a\tb"))
        assertFalse(isValidDisplayName("a\u0000b"))
    }

    @Test
    fun spacesInsideAName() {
        assertTrue(isValidDisplayName("Main  savings"))
    }

    @Test
    fun displayNameOfTrimsTheSurroundingWhitespace() {
        assertEquals("Savings", displayNameOf("  Savings \t"))
        assertEquals("Main  savings", displayNameOf(" Main  savings "))
    }

    @Test
    fun displayNameOfRefusesABlankName() {
        assertFailsWith<IllegalArgumentException> { displayNameOf("") }
        assertFailsWith<IllegalArgumentException> { displayNameOf("   ") }
    }

    @Test
    fun displayNameOfCountsTheLimitAfterTrimming() {
        assertEquals("x".repeat(DISPLAY_NAME_MAX_LENGTH), displayNameOf("  " + "x".repeat(DISPLAY_NAME_MAX_LENGTH) + "  "))
        assertFailsWith<IllegalArgumentException> { displayNameOf("x".repeat(DISPLAY_NAME_MAX_LENGTH + 1)) }
    }

    @Test
    fun anAutoNameIsTheCreationNameAndTheIndexFromZero() {
        assertEquals("Seed Wallet 0", autoAccountName("Seed Wallet", 0))
        assertEquals("Seed Wallet 1", autoAccountName("Seed Wallet", 1))
        assertEquals("WALLET 1 999", autoAccountName("WALLET 1", 999))
    }
}
