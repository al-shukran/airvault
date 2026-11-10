package app.lensly.ui.wallet

import kotlin.test.assertEquals
import org.junit.Test

class EffectiveWalletNameTest {

    @Test
    fun emptyInputUsesTheSuggestedName() {
        assertEquals("WALLET 1", effectiveWalletName("", "WALLET 1"))
    }

    @Test
    fun typedNameIsTheWholeName() {
        assertEquals("TEST KEY", effectiveWalletName("TEST KEY", "WALLET 1"))
    }

    @Test
    fun typedNameNeverIncludesTheSuggestion() {
        assertEquals("TEST KEY", effectiveWalletName("TEST KEY", null))
    }

    @Test
    fun whitespaceOnlyInputUsesTheSuggestedName() {
        assertEquals("WALLET 1", effectiveWalletName("   ", "WALLET 1"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("TEST KEY", effectiveWalletName("  TEST KEY ", "WALLET 1"))
    }

    @Test
    fun noSuggestionAndNoInputFallsBackToWallet() {
        assertEquals("WALLET", effectiveWalletName("", null))
        assertEquals("WALLET", effectiveWalletName("  ", null))
    }
}
