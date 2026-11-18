package app.lumio.data.config

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The "Prefer RPC over Explorers" choice: off by default, and kept in the vault once the owner sets it. */
class PreferRpcStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val session by lazy {
        VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() })
    }

    @Test
    fun explorersAreAskedUnlessTheOwnerPrefersRpc() {
        assertFalse(PreferRpcStore.read(session))

        PreferRpcStore.write(session, true)
        assertTrue(PreferRpcStore.read(session))

        PreferRpcStore.write(session, false)
        assertFalse(PreferRpcStore.read(session))
    }
}
