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
    /** Name of the chain logo drawable, such as "chain_base" (see RegistryIcons.kt). Null shows the Ethereum logo. */
    val icon: String? = null,
) {
    init {
        require(chainId > 0) { "chainId must be positive: $chainId" }
        require(nativeDecimals in 0..255) { "nativeDecimals out of range: $nativeDecimals" }
    }
}
