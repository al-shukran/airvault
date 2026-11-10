package app.lensly.core.vault

import kotlin.test.assertEquals
import org.junit.Test

class PasswordPolicyTest {
    @Test
    fun refusesFewerThanTwelveCodePointsFirst() {
        assertEquals(
            PolicyResult.Refused(PolicyReason.TOO_SHORT),
            PasswordPolicy.evaluate(codePoints = 11, score = 4, guessesLog10 = 40.0),
        )
    }

    @Test
    fun refusesScoreBelowThree() {
        assertEquals(
            PolicyResult.Refused(PolicyReason.WEAK_SCORE),
            PasswordPolicy.evaluate(codePoints = 16, score = 2, guessesLog10 = 30.0),
        )
        assertEquals(
            PolicyResult.Accepted,
            PasswordPolicy.evaluate(codePoints = 16, score = 3, guessesLog10 = 30.0),
        )
    }

    @Test
    fun refusesUnderFiftyBitsEvenWhenScoreIsHigh() {
        // 14 * log2(10) is about 46.5 bits.
        assertEquals(
            PolicyResult.Refused(PolicyReason.LOW_ENTROPY),
            PasswordPolicy.evaluate(codePoints = 16, score = 4, guessesLog10 = 14.0),
        )
    }

    @Test
    fun entropyThresholdSitsBetweenFifteenAndFifteenPointOneLog10Guesses() {
        // 15.0 * log2(10) is about 49.8 bits, 15.1 * log2(10) is about 50.2 bits.
        assertEquals(
            PolicyResult.Refused(PolicyReason.LOW_ENTROPY),
            PasswordPolicy.evaluate(codePoints = 12, score = 3, guessesLog10 = 15.0),
        )
        assertEquals(
            PolicyResult.Accepted,
            PasswordPolicy.evaluate(codePoints = 12, score = 3, guessesLog10 = 15.1),
        )
    }

    @Test
    fun checkCountsCodePointsNotUtf16Units() {
        // Eleven emoji are 22 UTF-16 units but only 11 code points.
        assertEquals(
            PolicyResult.Refused(PolicyReason.TOO_SHORT),
            PasswordPolicy.check("😀".repeat(11).toCharArray()),
        )
    }

    @Test
    fun checkRefusesShortPassword() {
        assertEquals(
            PolicyResult.Refused(PolicyReason.TOO_SHORT),
            PasswordPolicy.check("Tr0ub4dor&3".toCharArray()),
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
    fun checkLeavesTheArrayUnchanged() {
        val password = "q7Vn!2Lx#Rb9Tm4Wc8Zp".toCharArray()
        PasswordPolicy.check(password)
        assertEquals("q7Vn!2Lx#Rb9Tm4Wc8Zp", password.concatToString())
    }
}
