package app.lensly.app

import app.lensly.core.btc.BtcNetwork

/**
 * The name of a chain family as the screens of both apps show it: "Bitcoin", "Ethereum", "Solana" or "Algorand". An EVM address is one account
 * on every EVM network, so the family is named, never a network.
 */
fun familyLabel(namespace: String): String = when (namespace) {
    "bip122" -> "Bitcoin"
    "eip155" -> "Ethereum"
    "solana" -> "Solana"
    "algorand" -> "Algorand"
    else -> error("No family is named for $namespace")
}

/** The name of a Bitcoin network a review or the Address Book form is for. Testnet4 is named as the test network. */
fun bitcoinNetworkName(network: BtcNetwork): String = when (network) {
    BtcNetwork.MAINNET -> "Bitcoin Mainnet"
    BtcNetwork.TESTNET4 -> "Bitcoin Testnet4"
}

/**
 * The chain the Address Book names under an entry's name, or null when [address] is not a valid address. It is the family, except for a Bitcoin
 * address on testnet4: a Bitcoin address is on one network, so a test address is not listed as "Bitcoin" beside a main one.
 */
fun addressChainLabel(address: String): String? {
    val family = chainOfAddress(address)?.let { familyLabel(it.namespace) } ?: return null
    return if (btcNetworkOf(address) == BtcNetwork.TESTNET4) bitcoinNetworkName(BtcNetwork.TESTNET4) else family
}
