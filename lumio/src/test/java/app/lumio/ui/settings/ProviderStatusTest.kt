package app.lumio.ui.settings

import app.lumio.data.config.ProviderConfig
import app.lumio.data.provider.Trait
import app.lumio.data.sched.ProviderHealth
import org.junit.Assert.assertEquals
import org.junit.Test

/** The status the settings show for a provider: its switch and key first, then what its scheduler knows. */
class ProviderStatusTest {

    private val keyless = setOf(Trait.KEYLESS)
    private val keyRequired = setOf(Trait.KEY_REQUIRED)
    private val noCalls = ProviderHealth(score = null, coolingMs = 0L, circuitOpenMs = 0L)

    @Test
    fun aSwitchedOffProviderIsOffWhateverItsHealth() {
        assertEquals(ProviderStatus.Off, providerStatus(keyless, ProviderConfig(enabled = false), ProviderHealth(1.0, 5L, 5L)))
    }

    @Test
    fun aKeyRequiredProviderWithoutAKeyNeedsOne() {
        assertEquals(ProviderStatus.NeedsKey, providerStatus(keyRequired, ProviderConfig(), noCalls))
        assertEquals(ProviderStatus.NeedsKey, providerStatus(keyRequired, ProviderConfig(apiKey = "  "), noCalls))
    }

    @Test
    fun aProviderTheSchedulerDoesNotHoldIsNotActive() {
        assertEquals(ProviderStatus.NotActive, providerStatus(keyless, ProviderConfig(), null))
    }

    @Test
    fun anOpenCircuitShowsBeforeACooldown() {
        assertEquals(
            ProviderStatus.CircuitOpen(retryInMs = 60_000L),
            providerStatus(keyless, ProviderConfig(), ProviderHealth(score = 3.0, coolingMs = 2_000L, circuitOpenMs = 60_000L)),
        )
    }

    @Test
    fun aCooldownShowsWithItsTimeLeft() {
        assertEquals(
            ProviderStatus.CoolingDown(inMs = 4_000L),
            providerStatus(keyless, ProviderConfig(), ProviderHealth(score = 3.0, coolingMs = 4_000L, circuitOpenMs = 0L)),
        )
    }

    @Test
    fun aProviderWithACallBehindItShowsItsScore() {
        assertEquals(ProviderStatus.Healthy(score = 120.0), providerStatus(keyless, ProviderConfig(), ProviderHealth(120.0, 0L, 0L)))
    }

    @Test
    fun aProviderNoCallHasReachedIsReady() {
        assertEquals(ProviderStatus.Ready, providerStatus(keyless, ProviderConfig(), noCalls))
    }
}
