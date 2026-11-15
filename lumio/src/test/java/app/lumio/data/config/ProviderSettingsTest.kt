package app.lumio.data.config

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.data.containsBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val keyed = withApiKey(emptyMap(), "etherscan", "  k-123  ")

        assertEquals("k-123", keyed["etherscan"]?.apiKey)
        assertNull(withApiKey(keyed, "etherscan", "   ")["etherscan"]?.apiKey)
    }

    @Test
    fun aMoveSwapsNeighboursAndRenumbersTheGroup() {
        val group = listOf("a", "b", "c")

        val moved = withMoved(emptyMap(), group, "b", up = true)

        assertEquals(listOf("b", "a", "c"), orderedIds(group, moved))
        assertEquals(mapOf("a" to 1, "b" to 0, "c" to 2), moved.mapValues { it.value.priority })
    }

    @Test
    fun aProviderAtAnEndOfItsGroupDoesNotMove() {
        assertEquals(emptyMap<String, ProviderConfig>(), withMoved(emptyMap(), listOf("a", "b"), "a", up = true))
    }

    @Test
    fun aKeySavedThroughTheSettingsSurvivesTheStoreAndStaysSealed() {
        val store = ProviderConfigStore(session)
        store.save(withApiKey(withEnabled(emptyMap(), "etherscan", on = true), "etherscan", "k-marker-42"))

        assertEquals("k-marker-42", store.load()["etherscan"]?.apiKey)
        val file = session.vaultRecordFileForTesting(ProviderConfigStore.RECORD).readBytes()
        assertFalse(file.containsBytes("k-marker-42".toByteArray()))
    }
}
