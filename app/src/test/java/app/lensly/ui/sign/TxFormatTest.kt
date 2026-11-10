package app.lensly.ui.sign

import org.junit.Test
import kotlin.test.assertEquals

class TxFormatTest {

    @Test
    fun groupsAddressIntoFourCharacterChunks() {
        assertEquals(
            "0x12 3456 7890 abcd ef12 3456 7890 ABCD EF12 3456 78",
            groupAddress("0x1234567890abcdef1234567890ABCDEF12345678"),
        )
    }

    @Test
    fun keepsEveryCharacterOfTheAddress() {
        val address = "0x71C7656EC7ab88b098defB751B7401B5f6d8976F"
        assertEquals(address, groupAddress(address).replace(" ", ""))
    }
}
