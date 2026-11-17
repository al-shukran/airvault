package app.lensly.ui.sign

import app.lensly.app.isAddressValue
import app.lensly.ui.components.groupAddress
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class TxFormatTest {

    @Test
    fun assumedAmountNoteReadsTheWholeAmountWithOneDecimal() {
        assertEquals("≈ 1.0 if 18 decimals (assumption, not verified)", assumedAmountNote(BigInteger.TEN.pow(18)))
        assertEquals("≈ 0.0 if 18 decimals (assumption, not verified)", assumedAmountNote(BigInteger.ZERO))
        assertEquals(
            "≈ 0.00063 if 18 decimals (assumption, not verified)",
            assumedAmountNote(BigInteger.valueOf(630_000_000_000_000L)),
        )
    }

    @Test
    fun unknownFeeNoteSaysTheTotalMayBeHigher() {
        assertEquals(
            "≈ 0.00063 if 18 decimals (assumption, not verified). " +
                "The fee model is unknown, so the total may be higher.",
            unknownFeeNote(BigInteger.valueOf(630_000_000_000_000L)),
        )
    }

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
