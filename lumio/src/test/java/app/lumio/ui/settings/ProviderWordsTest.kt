package app.lumio.ui.settings

import app.lumio.data.provider.Trait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderWordsTest {

    @Test
    fun aHealthyOrNewProviderIsWorking() {
        assertEquals("✓ Working", providerStatusWord(ProviderStatus.Ready))
        assertEquals("✓ Working", providerStatusWord(ProviderStatus.Healthy(score = 1.5)))
    }

    @Test
    fun aProviderInACooldownOrOpenCircuitIsRateLimited() {
        assertEquals("◆ Rate-Limited", providerStatusWord(ProviderStatus.CoolingDown(inMs = 4_000)))
        assertEquals("◆ Rate-Limited", providerStatusWord(ProviderStatus.CircuitOpen(retryInMs = 9_000)))
    }

    @Test
    fun aSwitchedOffOrUnscheduledProviderIsOff() {
        assertEquals("– Off", providerStatusWord(ProviderStatus.Off))
        assertEquals("– Off", providerStatusWord(ProviderStatus.NotActive))
        assertEquals("– Off", providerStatusWord(ProviderStatus.NeedsKey))
    }

    @Test
    fun onlyAProviderThatNeedsAKeyCarriesTheNeedsKeyNote() {
        assertEquals("Needs API Key", providerStatusNote(ProviderStatus.NeedsKey))
        assertNull(providerStatusNote(ProviderStatus.Ready))
        assertNull(providerStatusNote(ProviderStatus.Off))
    }

    @Test
    fun aKnownProviderShowsItsNameAndAnUnknownOneItsID() {
        assertEquals("mempool.space", providerName("mempool"))
        assertEquals("CoinGecko", providerName("coingecko"))
        assertEquals("1rpc", providerName("1rpc"))
    }

    @Test
    fun aProviderThatNeedsNoKeyIsTaggedAsWorkingWithoutOne() {
        assertEquals(listOf("Works without an API Key"), providerTags("etherscan", setOf(Trait.KEY_OPTIONAL)))
    }

    @Test
    fun aProviderThatRequiresAKeyIsNotTaggedAsWorkingWithoutOne() {
        assertEquals(emptyList<String>(), providerTags("alchemy", setOf(Trait.KEY_REQUIRED)))
    }

    @Test
    fun blockscoutIsTheOnlyProviderTaggedAnonymous() {
        assertEquals(
            listOf("Works without an API Key", "Anonymous"),
            providerTags("blockscout", setOf(Trait.KEY_OPTIONAL)),
        )
        assertEquals(listOf("Works without an API Key"), providerTags("routescan", setOf(Trait.KEY_OPTIONAL)))
    }
}
