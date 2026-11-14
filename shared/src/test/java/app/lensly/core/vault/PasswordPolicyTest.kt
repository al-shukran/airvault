package app.lensly.core.vault

import kotlin.test.assertEquals
import org.junit.Test

class PasswordPolicyTest {
    @Test
    fun refusesFewerThanEightCodePointsFirst() {
        assertEquals(
            PolicyResult.Refused(PolicyReason.TOO_SHORT),
            PasswordPolicy.evaluate(codePoints = 7, score = 4, guessesLog10 = 40.0),
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
    fun refusesUnder28BitsEvenWhenScoreIsHigh() {
        // 8.0 * log2(10) is about 26.6 bits.
        assertEquals(
            PolicyResult.Refused(PolicyReason.LOW_ENTROPY),
            PasswordPolicy.evaluate(codePoints = 16, score = 4, guessesLog10 = 8.0),
        )
    }

    @Test
    fun entropyThresholdIs28Bits() {
        // 8.4 * log2(10) is about 27.9 bits, 8.5 * log2(10) is about 28.2 bits.
        assertEquals(
            PolicyResult.Refused(PolicyReason.LOW_ENTROPY),
            PasswordPolicy.evaluate(codePoints = 8, score = 2, guessesLog10 = 8.4),
        )
        assertEquals(
            PolicyResult.Accepted,
            PasswordPolicy.evaluate(codePoints = 8, score = 2, guessesLog10 = 8.5),
        )
    }

    @Test
    fun checkCountsCodePointsNotUtf16Units() {
        // Seven emoji are 14 UTF-16 units but only 7 code points.
        assertEquals(
            PolicyResult.Refused(PolicyReason.TOO_SHORT),
            PasswordPolicy.check("😀".repeat(7).toCharArray()),
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
    fun checkRefusesEightRepeatedCharacters() {
        assertEquals(
            PolicyResult.Refused(PolicyReason.WEAK_SCORE),
            PasswordPolicy.check("aaaaaaaa".toCharArray()),
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
    fun checkAcceptsShortMixedPassword() {
        assertEquals(
            PolicyResult.Accepted,
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
}
