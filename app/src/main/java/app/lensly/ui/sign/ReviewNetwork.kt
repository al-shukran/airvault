package app.lensly.ui.sign

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.registry.NetworkEntry

/**
 * The human-readable name of an EVM network, from the registry entry of its chain ID. A chain ID the registry does not hold is named as
 * unknown, with the ID, so the owner sees which chain the request names.
 */
internal fun evmNetworkName(network: NetworkEntry?, chainId: Long): String = network?.name ?: unknownNetworkName(chainId)

/** The name of a chain ID that the registry does not hold. */
internal fun unknownNetworkName(chainId: Long): String = "Unknown network (chain id $chainId)"

/** The name of a Bitcoin network a review is for. Testnet4 is named as the test network. */
internal fun bitcoinNetworkName(network: BtcNetwork): String = when (network) {
    BtcNetwork.MAINNET -> "Bitcoin Mainnet"
    BtcNetwork.TESTNET4 -> "Bitcoin Testnet4"
}

