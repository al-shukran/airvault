package app.lumio.ui.send

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class MonoAddressTest {
    private val address = "0x9414 2B4f 6653 16D3 304C 3a59 5ec8 3aC9 C804 6598"

    private fun spanned(text: String): List<String> = withMonoAddress(text).spanStyles.map { text.substring(it.start, it.end) }

    @Test
    fun theAddressInBracketsIsSetInMono() {
        assertEquals(listOf(address), spanned("0.25 MON to FelixStone ($address)"))
        assertEquals(listOf(address), spanned("In FelixStone ($address)"))
    }

    @Test
    fun aBareAddressIsSetInMono() {
        assertEquals(listOf(address), spanned("0.25 MON to $address"))
        assertEquals(listOf("bc1q 3x7m k2d9 8w4r t6yh p0ze n5va c8ju l4fg"), spanned("In bc1q 3x7m k2d9 8w4r t6yh p0ze n5va c8ju l4fg"))
    }

    @Test
    fun wordsAreLeftAlone() {
        assertTrue(spanned("0.25 MON to your nonce account").isEmpty())
        assertTrue(spanned("In Air One Two Three Four Five Six").isEmpty())
    }

    @Test
    fun theTextIsNeverChanged() {
        val text = "0.25 MON to FelixStone ($address)"
        assertEquals(text, withMonoAddress(text).text)
    }
}
