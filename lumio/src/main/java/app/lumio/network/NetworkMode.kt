package app.lumio.network

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.vault.VaultSession
import app.lumio.evm.EvmChain

/**
 * The side of the networks that Lumio shows: mainnet, or testnet. Mainnet shows the mainnet networks and their balances only, and testnet shows
 * the testnets only. A screen shows one side at a time, so a mainnet balance and a testnet balance are never added together.
 */
enum class NetworkMode {
    MAINNET,
    TESTNET;

    /** True when a network with this testnet flag belongs to this side. */
    fun holds(testnet: Boolean): Boolean = (this == TESTNET) == testnet

    /** True when the Bitcoin [network] belongs to this side. testnet4 is the testnet. */
    fun holds(network: BtcNetwork): Boolean = holds(network == BtcNetwork.TESTNET4)

    /** True when the EVM [chain] belongs to this side. */
    fun holds(chain: EvmChain): Boolean = holds(chain.testnet)

    /** The Bitcoin network of this side: testnet4 for testnet, mainnet otherwise. */
    fun btcNetwork(): BtcNetwork = if (this == TESTNET) BtcNetwork.TESTNET4 else BtcNetwork.MAINNET
}

/**
 * The side the owner chose. It is a vault record, so it is encrypted under the vault key. A missing or unknown record reads as mainnet, the default.
 */
object NetworkModeStore {
    /** The vault record that holds the chosen side. */
    const val RECORD = "lumio-network-mode"

    fun read(session: VaultSession): NetworkMode {
        val stored = session.getVaultRecord(RECORD)?.decodeToString()
        return NetworkMode.entries.firstOrNull { it.name == stored } ?: NetworkMode.MAINNET
    }

    fun write(session: VaultSession, mode: NetworkMode) {
        session.putVaultRecord(RECORD, mode.name.encodeToByteArray())
    }
}
