package app.lumio.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Test

class ClearAirAmountTest {

    @Test
    fun currencySignIsThePrefixAndTheFractionStartsAtThePoint() {
        assertEquals(AmountParts(prefix = "\$ ", whole = "149,769", fraction = ".10", suffix = ""), splitAmount("\$ 149,769.10"))
    }

    @Test
    fun unitAfterTheNumberIsTheSuffix() {
        assertEquals(AmountParts(prefix = "", whole = "0", fraction = ".25", suffix = " MON"), splitAmount("0.25 MON"))
    }

    @Test
    fun wholeNumberHasNoFraction() {
        assertEquals(AmountParts(prefix = "", whole = "1,000", fraction = "", suffix = ""), splitAmount("1,000"))
    }

    @Test
    fun leadingMinusStaysInTheWholeNumber() {
        assertEquals(AmountParts(prefix = "", whole = "-6", fraction = "", suffix = "%"), splitAmount("-6%"))
    }

    @Test
    fun unicodeMinusStaysInTheWholeNumber() {
        assertEquals(AmountParts(prefix = "", whole = "−6", fraction = "", suffix = "%"), splitAmount("−6%"))
    }

    @Test
    fun trailingPointIsTheFraction() {
        assertEquals(AmountParts(prefix = "\$", whole = "5", fraction = ".", suffix = ""), splitAmount("\$5."))
    }

    @Test
    fun textWithoutANumberIsAllPrefix() {
        assertEquals(AmountParts(prefix = "—", whole = "", fraction = "", suffix = ""), splitAmount("—"))
    }

    @Test
    fun emptyTextHasNoParts() {
        assertEquals(AmountParts(prefix = "", whole = "", fraction = "", suffix = ""), splitAmount(""))
    }

    @Test
    fun partsRebuildTheOriginalText() {
        for (text in listOf("\$ 149,769.10", "0.25 MON", "-6%", "−6%", "\$5.", "—", "")) {
            val parts = splitAmount(text)
            assertEquals(text, parts.prefix + parts.whole + parts.fraction + parts.suffix)
        }
    }
}
