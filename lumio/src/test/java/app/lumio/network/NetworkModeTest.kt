package app.lumio.network

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.evm.EvmChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The side of the networks: which networks each side holds, and the vault record that keeps the owner's choice. */
class NetworkModeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val session by lazy {
        VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() })
    }

    @Test
    fun aNewVaultReadsAsMainnet() {
        assertEquals(NetworkMode.MAINNET, NetworkModeStore.read(session))
    }

    @Test
    fun theChosenSideIsKept() {
        NetworkModeStore.write(session, NetworkMode.TESTNET)
        assertEquals(NetworkMode.TESTNET, NetworkModeStore.read(session))

        NetworkModeStore.write(session, NetworkMode.MAINNET)
        assertEquals(NetworkMode.MAINNET, NetworkModeStore.read(session))
    }

    @Test
    fun aSideHoldsOnlyItsOwnNetworks() {
        assertTrue(NetworkMode.MAINNET.holds(BtcNetwork.MAINNET))
        assertFalse(NetworkMode.MAINNET.holds(BtcNetwork.TESTNET4))
        assertTrue(NetworkMode.TESTNET.holds(BtcNetwork.TESTNET4))
        assertFalse(NetworkMode.TESTNET.holds(BtcNetwork.MAINNET))

        assertTrue(NetworkMode.MAINNET.holds(evm(testnet = false)))
        assertFalse(NetworkMode.MAINNET.holds(evm(testnet = true)))
        assertTrue(NetworkMode.TESTNET.holds(evm(testnet = true)))
        assertFalse(NetworkMode.TESTNET.holds(evm(testnet = false)))
    }

    private fun evm(testnet: Boolean) = EvmChain(
        chainId = if (testnet) 11155111L else 1L,
        name = if (testnet) "Sepolia" else "Ethereum",
        symbol = "ETH",
        decimals = 18,
        testnet = testnet,
        explorerUrl = null,
    )
}
