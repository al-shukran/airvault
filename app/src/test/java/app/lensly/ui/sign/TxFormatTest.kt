package app.lensly.ui.sign

import app.lensly.app.isAddressValue
import app.lensly.ui.components.groupAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class TxFormatTest {

    @Test
    fun groupsEvmAddressIntoFourCharacterChunksAfterThePrefix() {
        assertEquals(
            "0x1234 5678 90ab cdef 1234 5678 90AB CDEF 1234 5678",
            groupAddress("0x1234567890abcdef1234567890ABCDEF12345678"),
        )
    }

    @Test
    fun aShorterLastGroupIsKept() {
        assertEquals("0x1234 567", groupAddress("0x1234567"))
    }

    @Test
    fun aNonEvmAddressIsGroupedFromItsFirstCharacter() {
        assertEquals(
            "bc1q xy2k gdyg jrsq tzq2 n0yr f249 3p83 kkfj hx0w lh",
            groupAddress("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"),
        )
    }

    @Test
    fun keepsEveryCharacterOfTheAddress() {
        val address = "0x71C7656EC7ab88b098defB751B7401B5f6d8976F"
        assertEquals(address, groupAddress(address).replace(" ", ""))
    }

    @Test
    fun recognisesEvmAddressesInEitherCase() {
        assertTrue(isAddressValue("0x71C7656EC7ab88b098defB751B7401B5f6d8976F"))
        assertTrue(isAddressValue("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"))
    }

    @Test
    fun rejectsTextThatIsNotAnEvmAddress() {
        assertFalse(isAddressValue("1.5"))
        assertFalse(isAddressValue("0x71C7656EC7ab88b098defB751B7401B5f6d8976"))
        assertFalse(isAddressValue("71C7656EC7ab88b098defB751B7401B5f6d8976F00"))
        assertFalse(isAddressValue("0xZZC7656EC7ab88b098defB751B7401B5f6d8976F"))
    }
}
