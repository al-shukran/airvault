package app.lumio.data.config

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The earlier release's Bitcoin server overrides become the owner's own servers, once, and keep their network. */
class ProviderMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun session(): VaultSession = VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() })

    @Test
    fun anOverrideOfTheEarlierReleaseBecomesTheOwnersServerOfItsNetwork() {
        val session = session()
        session.putVaultRecord(ProviderConfigStore.LEGACY_SERVERS_RECORD, legacy(mapOf(BtcNetwork.TESTNET4 to "https://node.example.org/testnet4/api")))

        val configs = ProviderConfigStore(session).load()

        assertEquals(mapOf("custom-btc-testnet4" to ProviderConfig(baseUrl = "https://node.example.org/testnet4/api")), configs)
        assertEquals(configs, ProviderConfigStore(session).load(), "the migrated settings are saved, and read back as saved")
    }

    @Test
    fun aVaultWithNoOverridesHasNoSettings() {
        assertEquals(emptyMap(), ProviderConfigStore(session()).load())
    }

    /** The earlier record: a version byte, the count, then each override as a network code and its URL. */
    private fun legacy(overrides: Map<BtcNetwork, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(1)
            out.writeInt(overrides.size)
            for ((network, url) in overrides) {
                out.writeByte(network.ordinal)
                out.writeUTF(url)
            }
        }
        return bytes.toByteArray()
    }
}
