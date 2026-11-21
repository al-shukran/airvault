package app.airvault.chains.bitcoin

import app.airvault.core.model.AIRVAULT_API_VERSION
import app.airvault.core.model.AppId
import app.airvault.core.model.AssetId
import app.airvault.core.model.AssetSpec
import app.airvault.core.model.Capability
import app.airvault.core.model.ChainId
import app.airvault.core.model.ChainManifest
import app.airvault.core.model.Curve
import app.airvault.core.model.Direction
import app.airvault.core.model.KeyScope
import app.airvault.core.model.NetworkSpec
import app.airvault.core.model.PathTemplate
import app.airvault.core.model.UrTypeSpec

/** Bitcoin mainnet: the first 32 hex characters of its genesis block hash (CAIP-2 bip122, spec 4). */
val bitcoinMainnet = ChainId("bip122:000000000019d6689c085ae165831e93")

/** Bitcoin testnet4: the first 32 hex characters of its genesis block hash (CAIP-2 bip122, spec 4). */
val bitcoinTestnet4 = ChainId("bip122:00000000da84f2bafbbc53dee25a72ae")

/** The coin type of the Bitcoin mainnet (SLIP-44). */
private const val MAINNET_COIN_TYPE = 0

/** The coin type of every Bitcoin test network (SLIP-44). */
const val TESTNET_COIN_TYPE = 1

/** The BIP43 purposes of the Bitcoin account paths: BIP44 legacy, BIP49 nested segwit, BIP84 native segwit and BIP86 taproot. */
private val BITCOIN_PURPOSES = listOf(44, 49, 84, 86)

/**
 * The Bitcoin chain app's manifest (spec 2.1, 11.1), the one declaration both Lensly and Lumio read. A PSBT request is `psbt`, the registry's
 * current name, or `crypto-psbt`, the retired name that Lumio writes. The response is the signed PSBT under the name its request used,
 * so the response declares none: the registry keeps each UR type to one entry. Watch-only export is the extended-key pairing that Lumio
 * reads (spec 7.5).
 */
val bitcoinManifest: ChainManifest = ChainManifest(
    id = AppId("bitcoin"),
    displayName = "Bitcoin",
    apiVersion = AIRVAULT_API_VERSION,
    appVersion = "1.0.0",
    namespaces = setOf("bip122"),
    networks = listOf(
        bitcoinNetwork(chain = bitcoinMainnet, name = "Bitcoin", testnet = false, coinType = MAINNET_COIN_TYPE),
        bitcoinNetwork(chain = bitcoinTestnet4, name = "Bitcoin Testnet4", testnet = true, coinType = TESTNET_COIN_TYPE),
    ),
    keys = listOf(
        KeyScope(
            curve = Curve.SECP256K1,
            pathPrefixes = BITCOIN_PURPOSES.flatMap { purpose ->
                listOf(MAINNET_COIN_TYPE, TESTNET_COIN_TYPE).map { coin -> PathTemplate("m/$purpose'/$coin'/*'") }
            },
            purpose = "Bitcoin account keys",
        ),
    ),
    capabilities = setOf(Capability.WATCH_ONLY_EXPORT),
    requestTypes = listOf(UrTypeSpec("psbt", Direction.REQUEST), UrTypeSpec("crypto-psbt", Direction.REQUEST)),
    responseTypes = emptyList(),
    fields = emptyList(),
)

/** A Bitcoin network. Its coin type is 0 on mainnet and 1 on the test networks, as BIP44 asks, and its native coin is BTC or tBTC. */
private fun bitcoinNetwork(chain: ChainId, name: String, testnet: Boolean, coinType: Int) = NetworkSpec(
    chainId = chain,
    name = name,
    testnet = testnet,
    nativeAsset = AssetSpec.Native(
        id = AssetId("${chain.caip2}/slip44:$coinType"),
        chain = chain,
        symbol = if (testnet) "tBTC" else "BTC",
        decimals = 8,
    ),
    slip44 = coinType,
)
