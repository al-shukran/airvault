package app.lumio.data.config

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.data.containsBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The settings the screen writes (the switch, the order and the key), and what the store keeps of them. */
class ProviderSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val session by lazy {
        VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 5 + 2).toByte() })
    }

    @Test
    fun aSwitchChangesOnlyThatProvider() {
        val configs = withEnabled(mapOf("a" to ProviderConfig(priority = 2)), "b", on = false)

        assertEquals(ProviderConfig(enabled = false), configs["b"])
        assertEquals(ProviderConfig(priority = 2), configs["a"])
    }

    @Test
    fun aTypedKeyIsTrimmedAndABlankKeyRemovesIt() {
        val keyed = withApiKey(emptyMap(), "etherscan", "  k-123  ", keyRequired = true)

        assertEquals("k-123", keyed["etherscan"]?.apiKey)
        assertNull(withApiKey(keyed, "etherscan", "   ", keyRequired = true)["etherscan"]?.apiKey)
    }

    @Test
    fun aKeyRequiredProviderIsSwitchedOnByItsKeyAndOffWhenItIsRemoved() {
        val keyed = withApiKey(mapOf("etherscan" to ProviderConfig(enabled = false)), "etherscan", " k-123 ", keyRequired = true)

        assertEquals(ProviderConfig(enabled = true, apiKey = "k-123"), keyed["etherscan"])
        assertEquals(ProviderConfig(enabled = false), withApiKey(keyed, "etherscan", "   ", keyRequired = true)["etherscan"])
    }

    @Test
    fun aKeyOptionalProviderKeepsItsSwitchWhenItsKeyChanges() {
        val cleared = withApiKey(mapOf("blockscout" to ProviderConfig(apiKey = "k-1")), "blockscout", "", keyRequired = false)
        val keyedOff = withApiKey(mapOf("blockscout" to ProviderConfig(enabled = false)), "blockscout", "k-2", keyRequired = false)

        assertEquals(ProviderConfig(), cleared["blockscout"])
        assertFalse(keyedOff.getValue("blockscout").enabled)
    }

    @Test
    fun aDragSetsTheOrderOfTheGroupAndRenumbersIt() {
        val group = listOf("a", "b", "c")

        val ordered = withOrderWithin(emptyMap(), group, listOf("b", "a", "c"))

        assertEquals(listOf("b", "a", "c"), orderedIds(group, ordered))
        assertEquals(mapOf("a" to 1, "b" to 0, "c" to 2), ordered.mapValues { it.value.priority })
    }

    @Test
    fun aDragOfSomeRowsKeepsTheOthersInTheirPlaces() {
        // "b" holds the middle place and stays there, while "c" and "a" trade the two ends.
        val ordered = withOrderWithin(emptyMap(), listOf("a", "b", "c"), listOf("c", "a"))

        assertEquals(listOf("c", "b", "a"), orderedIds(listOf("a", "b", "c"), ordered))
    }

    @Test
    fun anOrderNamingAProviderOutsideTheGroupOrOneTwiceIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { withOrderWithin(emptyMap(), listOf("a", "b"), listOf("a", "z")) }
        assertThrows(IllegalArgumentException::class.java) { withOrderWithin(emptyMap(), listOf("a", "b"), listOf("a", "a")) }
    }

    @Test
    fun aKeySavedThroughTheSettingsSurvivesTheStoreAndStaysSealed() {
        val store = ProviderConfigStore(session)
        store.save(withApiKey(withEnabled(emptyMap(), "etherscan", on = true), "etherscan", "k-marker-42", keyRequired = true))

        assertEquals("k-marker-42", store.load()["etherscan"]?.apiKey)
        val file = session.vaultRecordFileForTesting(ProviderConfigStore.RECORD).readBytes()
        assertFalse(file.containsBytes("k-marker-42".toByteArray()))
    }
}
