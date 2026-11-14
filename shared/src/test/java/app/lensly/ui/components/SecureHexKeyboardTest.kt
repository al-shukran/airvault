package app.lensly.ui.components

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class SecureHexKeyboardTest {

    private val keys = HEX_DIGIT_ROW + HEX_LETTER_ROW

    @Test
    fun theKeysHoldEveryHexDigitOnce() {
        for (digit in "0123456789abcdef") {
            assertTrue(digit in keys, "no key for $digit")
        }
        assertEquals(keys.length, keys.toSet().size, "a key is repeated")
    }

    @Test
    fun theKeysHoldTheXOfTheZeroXPrefixAndNoOtherLetters() {
        assertTrue('x' in keys)
        val others = keys.filter { it !in "0123456789abcdefx" }
        assertEquals("", others)
    }
}
