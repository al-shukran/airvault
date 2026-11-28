package app.lumio.ui.kit

import kotlin.test.assertEquals
import org.junit.Test

class ClearAirAddressLineTest {
    private val evm = "0x1593D398aF7c02E1b9C4d6E8f0A27b913eA25343"

    @Test
    fun anEvmAddressIsItsTwoEndsInGroupsOfFourAndCanBreakOnlyAfterTheEllipsis() {
        val text = addressLineText(evm)

        assertEquals("0x1593 D398 … 3eA2 5343", text.replace(' ', ' '))
        assertEquals(listOf("0x1593 D398 …", "3eA2 5343"), text.split(' '), "the one breakable space is the one after the ellipsis")
    }

    @Test
    fun anotherEncodingKeepsSixAndFourAndCanBreakOnlyAfterTheEllipsis() {
        assertEquals("bc1qar…​5mdq", addressLineText("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
    }

    @Test
    fun aScreenReaderHearsToForTheEllipsis() {
        assertEquals("0x1593 D398 to 3eA2 5343", addressLineSpoken(evm))
        assertEquals("bc1qar to 5mdq", addressLineSpoken("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
    }
}
