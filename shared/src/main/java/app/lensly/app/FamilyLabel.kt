package app.lensly.app

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
