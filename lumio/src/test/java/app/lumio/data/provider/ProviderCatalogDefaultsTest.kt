package app.lumio.data.provider

import app.lumio.data.config.ProviderConfig
import app.lumio.data.config.configOf
import app.lumio.data.config.withApiKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The defaults the settings start from: a provider that needs a key is switched off until one is saved, and the others stay on. */
class ProviderCatalogDefaultsTest {

    @Test
    fun aProviderThatNeedsItsKeyStartsSwitchedOff() {
        val configs = ProviderCatalog.withDefaults(emptyMap())

        assertFalse(configOf(configs, "etherscan").enabled)
        assertFalse(configOf(configs, "blockchair").enabled)
        assertFalse(configOf(configs, "ankr").enabled)
    }

    @Test
    fun aProviderThatWorksWithoutAKeyStartsSwitchedOn() {
        val configs = ProviderCatalog.withDefaults(emptyMap())

        assertTrue(configOf(configs, "blockscout").enabled)
        assertTrue(configOf(configs, "coingecko").enabled)
        assertTrue(configOf(configs, "mempool").enabled)
    }

    @Test
    fun aSavedSettingReplacesTheDefaultOfItsProvider() {
        val saved = mapOf("etherscan" to ProviderConfig(enabled = true, apiKey = "k-1"))

        assertEquals(ProviderConfig(enabled = true, apiKey = "k-1"), ProviderCatalog.withDefaults(saved)["etherscan"])
    }

    @Test
    fun thePriceDefaultsStillApply() {
        val configs = ProviderCatalog.withDefaults(emptyMap())

        assertEquals(0, configOf(configs, "coingecko").priority)
        assertEquals(1, configOf(configs, "defillama").priority)
    }

    @Test
    fun savingAKeyToADefaultedProviderTurnsItOnAndRemovingItTurnsItOff() {
        val keyed = withApiKey(ProviderCatalog.withDefaults(emptyMap()), "etherscan", "k-1", keyRequired = true)
        val cleared = withApiKey(keyed, "etherscan", "", keyRequired = true)

        assertTrue(configOf(keyed, "etherscan").enabled)
        assertFalse(configOf(cleared, "etherscan").enabled)
    }
}
