package app.lumio.data.config

import kotlin.test.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FiatTest {
    @Test
    fun usdIsWrittenAsItAlwaysWas() {
        assertEquals("$1,234.56", Fiat.USD.amount(1234.56))
        assertEquals("$0.00", Fiat.USD.amount(0.0))
    }

    @Test
    fun anAmountIsConvertedAtTheRateAndWrittenWithItsSymbol() {
        assertEquals("€900.00", Fiat.of(FiatCurrency.EUR, 0.9).amount(1000.0))
        assertEquals("£1,250.00", Fiat.of(FiatCurrency.GBP, 0.8).amount(1562.5))
    }

    @Test
    fun yenHasNoDecimals() {
        assertEquals("¥1,500", Fiat.of(FiatCurrency.JPY, 150.0).amount(10.0))
    }

    @Test
    fun francsHaveTheirCodeAsAWordBeforeTheAmount() {
        assertEquals("CHF 900.00", Fiat.of(FiatCurrency.CHF, 0.9).amount(1000.0))
    }

    @Test
    fun withNoRateTheAmountFallsBackToUsDollars() {
        assertEquals("$1,000.00", Fiat.of(FiatCurrency.EUR, null).amount(1000.0))
    }

    @Test
    fun aRateThatIsNotPositiveFallsBackToUsDollars() {
        assertEquals("$1,000.00", Fiat.of(FiatCurrency.EUR, 0.0).amount(1000.0))
        assertEquals("$1,000.00", Fiat.of(FiatCurrency.EUR, -0.9).amount(1000.0))
    }

    @Test
    fun usdNeedsNoRate() {
        assertEquals("$1,000.00", Fiat.of(FiatCurrency.USD, null).amount(1000.0))
    }

    @Test
    fun aBalanceBelowTheThresholdIsLeftOutAndOneAtOrAboveItIsNot() {
        val settings = DisplaySettings(smallBalanceUsd = 1.0)
        assertTrue(settings.hidesSmallBalance(0.99))
        assertFalse(settings.hidesSmallBalance(1.0))
        assertFalse(settings.hidesSmallBalance(250.0))
    }

    @Test
    fun aZeroThresholdShowsEveryBalanceAndAnUnknownValueIsNeverLeftOut() {
        assertFalse(DisplaySettings(smallBalanceUsd = 0.0).hidesSmallBalance(0.0))
        assertFalse(DisplaySettings(smallBalanceUsd = 5.0).hidesSmallBalance(null))
    }
}
