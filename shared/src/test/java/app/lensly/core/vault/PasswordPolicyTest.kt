package app.lensly.core.vault

import kotlin.math.log10
import kotlin.test.assertEquals
import org.junit.Test

class PasswordPolicyTest {
    @Test
    fun refusesFewerThanTwelveCodePointsFirst() {
        assertEquals(
            PolicyResult.Refused(PolicyReason.TOO_SHORT),
            PasswordPolicy.evaluate(codePoints = 11, score = 4, guessesLog10 = 40.0),
        )
        assertEquals(
            PolicyResult.Accepted,
            PasswordPolicy.evaluate(codePoints = 12, score = 4, guessesLog10 = 40.0),
        )
    }

    @Test
    fun refusesScoreBelowTwo() {
        assertEquals(
            PolicyResult.Refused(PolicyReason.WEAK_SCORE),
            PasswordPolicy.evaluate(codePoints = 16, score = 1, guessesLog10 = 30.0),
        )
        assertEquals(
            PolicyResult.Accepted,
            PasswordPolicy.evaluate(codePoints = 16, score = 2, guessesLog10 = 30.0),
        )
    }

    @Test
    fun refusesUnder40BitsEvenWhenScoreIsHigh() {
        // 8.0 * log2(10) is about 26.6 bits.
        assertEquals(
            PolicyResult.Refused(PolicyReason.LOW_ENTROPY),
            PasswordPolicy.evaluate(codePoints = 16, score = 4, guessesLog10 = 8.0),
        )
    }

    @Test
    fun refusesA30BitPassword() {
        assertEquals(
            PolicyResult.Refused(PolicyReason.LOW_ENTROPY),
            PasswordPolicy.evaluate(codePoints = 16, score = 4, guessesLog10 = bitsAsLog10(30.0)),
        )
    }

    @Test
    fun acceptsA45BitPassword() {
        assertEquals(
            PolicyResult.Accepted,
            PasswordPolicy.evaluate(codePoints = 16, score = 2, guessesLog10 = bitsAsLog10(45.0)),
        )
    }

    @Test
    fun entropyThresholdIs40Bits() {
        // 12.0 * log2(10) is about 39.9 bits, 12.1 * log2(10) is about 40.2 bits.
        assertEquals(
            PolicyResult.Refused(PolicyReason.LOW_ENTROPY),
            PasswordPolicy.evaluate(codePoints = 12, score = 2, guessesLog10 = 12.0),
        )
        assertEquals(
            PolicyResult.Accepted,
            PasswordPolicy.evaluate(codePoints = 12, score = 2, guessesLog10 = 12.1),
        )
    }

    @Test
    fun checkCountsCodePointsNotUtf16Units() {
        // Eleven emoji are 22 UTF-16 units but only 11 code points, one short of the minimum.
        assertEquals(
            PolicyResult.Refused(PolicyReason.TOO_SHORT),
            PasswordPolicy.check("😀".repeat(11).toCharArray()),
        )
    }

    @Test
    fun checkRefusesShortPassword() {
        assertEquals(
            PolicyResult.Refused(PolicyReason.TOO_SHORT),
            PasswordPolicy.check("Tr0ub&3".toCharArray()),
        )
    }

    @Test
    fun checkRefusesRepeatedCharacters() {
        assertEquals(
            PolicyResult.Refused(PolicyReason.WEAK_SCORE),
            PasswordPolicy.check("a".repeat(20).toCharArray()),
        )
    }

    @Test
    fun checkRefusesTwelveRepeatedCharacters() {
        assertEquals(
            PolicyResult.Refused(PolicyReason.WEAK_SCORE),
            PasswordPolicy.check("a".repeat(12).toCharArray()),
        )
    }

    @Test
    fun checkRefusesDictionaryPassword() {
        assertEquals(
            PolicyResult.Refused(PolicyReason.WEAK_SCORE),
            PasswordPolicy.check("password1234".toCharArray()),
        )
    }

    @Test
    fun checkAcceptsRandomPassword() {
        assertEquals(
            PolicyResult.Accepted,
            PasswordPolicy.check("q7Vn!2Lx#Rb9Tm4Wc8Zp".toCharArray()),
        )
    }

    @Test
    fun checkRefusesShortMixedPasswordBelowTheFortyBitFloor() {
        // Accepted at the old 28-bit floor. Its estimate is under 40 bits, so it is refused now.
        assertEquals(
            PolicyResult.Refused(PolicyReason.LOW_ENTROPY),
            PasswordPolicy.check("Lensly-2026!".toCharArray()),
        )
    }

    @Test
    fun checkAcceptsThreeWordPassphrase() {
        assertEquals(
            PolicyResult.Accepted,
            PasswordPolicy.check("blue tiger lamp".toCharArray()),
        )
    }

    @Test
    fun checkLeavesTheArrayUnchanged() {
        val password = "q7Vn!2Lx#Rb9Tm4Wc8Zp".toCharArray()
        PasswordPolicy.check(password)
        assertEquals("q7Vn!2Lx#Rb9Tm4Wc8Zp", password.concatToString())
    }

    /** The guess count of a password of [bits] bits, as zxcvbn reports it on a log10 scale. */
    private fun bitsAsLog10(bits: Double): Double = bits * log10(2.0)
}
