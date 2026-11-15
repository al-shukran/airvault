package app.lumio.data.config

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.data.containsBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The provider settings as a vault record: what they hold, and what the stored file shows. */
class ProviderConfigStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val session by lazy {
        VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 3 + 1).toByte() })
    }

    private val store by lazy { ProviderConfigStore(session) }

    @Test
    fun aMissingRecordLoadsAsNoSettings() {
        assertEquals(emptyMap<String, ProviderConfig>(), store.load())
    }

    @Test
    fun settingsSurviveASaveAndALoad() {
        val configs = mapOf(
            "mempool" to ProviderConfig(priority = 1),
            "custom" to ProviderConfig(
                priority = 0,
                weight = 3,
                apiKey = "fake-key",
                baseUrl = "https://example.invalid/api",
                limits = Limits(rps = 2.0, burst = 4, maxConcurrent = 2),
            ),
        )

        store.save(configs)

        assertEquals(configs, store.load())
    }

    @Test
    fun theApiKeyIsSealedInTheVaultRecord() {
        store.save(mapOf("etherscan" to ProviderConfig(apiKey = "k-marker")))

        val file = session.vaultRecordFileForTesting(ProviderConfigStore.RECORD).readBytes()

        assertFalse(file.containsBytes("k-marker".toByteArray()))
    }

    @Test
    fun aSettingPrintsWithoutItsKeyOrItsBaseUrl() {
        val text = ProviderConfig(apiKey = "k-marker", baseUrl = "https://example.invalid/k-marker").toString()

        assertFalse(text.contains("k-marker"))
    }
}
