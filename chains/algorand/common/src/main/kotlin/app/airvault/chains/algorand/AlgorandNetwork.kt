package app.airvault.chains.algorand

/**
 * The networks Lensly signs for, named by their genesis ID. A transaction for any other genesis ID is refused, and so is one whose genesis
 * hash is not [genesisHash]. Both pins were read from the `genesis-id` and `genesis-hash` of `/v2/transactions/params` on
 * testnet-api.algonode.cloud and mainnet-api.algonode.cloud, and match the second node provider, nodely.dev.
 */
enum class AlgorandNetwork(val genesisId: String, genesisHashBase64: String, val displayName: String, val testnet: Boolean) {
    MAINNET("mainnet-v1.0", "wGHE2Pwdvd7S12BL5FaOP20EGYesN73ktiC1qzkkit8=", "Algorand Mainnet", testnet = false),
    TESTNET("testnet-v1.0", "SGO1GKSzyE7IEPItTxCByw9x8FmnrCDexi9/cOUJOiI=", "Algorand Testnet", testnet = true);

    /** The 32-byte genesis hash of this network, the `gh` every transaction on it carries. */
    val genesisHash: ByteArray = java.util.Base64.getDecoder().decode(genesisHashBase64)

    companion object {
        /** The network with [genesisId], or null when Lensly does not sign for it. */
        fun ofGenesisId(genesisId: String): AlgorandNetwork? = entries.firstOrNull { it.genesisId == genesisId }
    }
}
