package app.lensly.ui.sign

import app.lensly.core.btc.BtcNetwork

/** The name of a chain ID that the registry does not hold. */
internal fun unknownNetworkName(chainId: Long): String = "Unknown network (chain id $chainId)"

/** The name of a Bitcoin network a review is for. Testnet4 is named as the test network. */
internal fun bitcoinNetworkName(network: BtcNetwork): String = when (network) {
    BtcNetwork.MAINNET -> "Bitcoin Mainnet"
    BtcNetwork.TESTNET4 -> "Bitcoin Testnet4"
}

