package app.lumio.ui.firstrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewPasswordTest {

    @Test
    fun anEmptyOrShortPasswordIsTooShort() {
        assertEquals(NewPasswordProblem.TooShort, newPasswordProblem("", ""))
        assertEquals(NewPasswordProblem.TooShort, newPasswordProblem("short", "short"))
    }

    @Test
    fun aGuessablePasswordIsTooWeak() {
        assertEquals(NewPasswordProblem.TooWeak, newPasswordProblem("password1234", "password1234"))
    }

    @Test
    fun aRepeatThatDiffersIsAMismatch() {
        assertEquals(NewPasswordProblem.Mismatch, newPasswordProblem(STRONG, "$STRONG!"))
        assertEquals(NewPasswordProblem.Mismatch, newPasswordProblem(STRONG, ""))
    }

    @Test
    fun anAcceptedPasswordRepeatedExactlyHasNoProblem() {
        assertNull(newPasswordProblem(STRONG, STRONG))
    }

    @Test
    fun theStrengthLineFillsAsThePasswordIsAcceptedAndStaysFullFromThere() {
        assertEquals(0, strengthSegments(""))
        assertEquals(1, strengthSegments("abc"))
        assertEquals(4, strengthSegments(STRONG))
    }

    private companion object {
        /** A password the policy accepts, the same test vector the vault tests use for their Duress Password. */
        const val STRONG = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
    }
}
