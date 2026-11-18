package app.airvault.core.model

/** The version of the chain-app contract this core implements. The registry refuses any other `apiVersion` (spec 2.4). */
const val AIRVAULT_API_VERSION: Int = 1

/**
 * The single declaration of a chain app (spec 2.1), shared by its signer and wallet sides.
 *
 * [sharesKeysWith] names apps whose key scopes may overlap this one's (spec 2.2, rule 5). It is empty unless an app
 * declares that link.
 */
data class ChainManifest(
    val id: AppId,
    val displayName: String,
    val apiVersion: Int,
    val appVersion: String,
    val namespaces: Set<String>,
    val networks: List<NetworkSpec>,
    val keys: List<KeyScope>,
    val capabilities: Set<Capability>,
    val requestTypes: List<UrTypeSpec>,
    val responseTypes: List<UrTypeSpec>,
    val fields: List<FieldSpec>,
    val sharesKeysWith: Set<AppId> = emptySet(),
)

/** One concrete network of an app, with the display data that pickers and the address book show (spec 2.1). */
data class NetworkSpec(
    val chainId: ChainId,
    val name: String,
    val testnet: Boolean,
    val nativeAsset: AssetSpec,
    val slip44: Int,
)

/**
 * The assets a network declares (spec 5.1). Only the native coin exists so far. Token variants arrive with the
 * catalogues that need them.
 */
sealed interface AssetSpec {
    val id: AssetId
    val chain: ChainId

    /** The chain's own coin, such as ALGO. [decimals] is display data; amounts are always base units. */
    data class Native(
        override val id: AssetId,
        override val chain: ChainId,
        val symbol: String,
        val decimals: Int,
    ) : AssetSpec
}
