package app.lensly.core.registry

import kotlinx.serialization.Serializable

/** One EVM network the vault will sign for. Immutable catalog data. */
@Serializable
data class NetworkEntry(
    val chainId: Long,
    val name: String,
    val nativeSymbol: String,
    val nativeDecimals: Int = 18,
    val feeModel: FeeModel,
    val explorerName: String?,
    val testnet: Boolean,
    /** Whether Lumio's Explore chains group lists this network even when no account sits on it. */
    val featured: Boolean,
    val kind: NetworkKind,
    /** The network a [NetworkKind.LAYER_2] settles to, such as 1 for Ethereum. Null for a Layer 1. */
    val parentChainId: Long?,
    /** Name of the chain logo drawable, such as "chain_base" (see RegistryIcons.kt). Null shows a letter of the name instead. */
    val icon: String? = null,
) {
    init {
        require(chainId > 0) { "chainId must be positive: $chainId" }
        require(nativeDecimals in 0..255) { "nativeDecimals out of range: $nativeDecimals" }
        require((kind == NetworkKind.LAYER_2) == (parentChainId != null)) {
            "chain $chainId: a Layer 2 needs a parent chain, and a Layer 1 has none"
        }
    }
}
