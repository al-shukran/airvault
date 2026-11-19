package app.airvault.chains.bitcoin.signer

import app.airvault.core.model.AIRVAULT_API_VERSION
import app.airvault.core.model.AppId
import app.airvault.core.model.AssetId
import app.airvault.core.model.AssetSpec
import app.airvault.core.model.ChainId
import app.airvault.core.model.ChainManifest
import app.airvault.core.model.Curve
import app.airvault.core.model.Direction
import app.airvault.core.model.KeyScope
import app.airvault.core.model.NetworkSpec
import app.airvault.core.model.PathTemplate
import app.airvault.core.model.UrTypeSpec

/** The BIP43 purposes of the Bitcoin account paths: BIP44 legacy, BIP49 nested segwit, BIP84 native segwit and BIP86 taproot. */
private val BITCOIN_PURPOSES = listOf(44, 49, 84, 86)

/** The coin types of the Bitcoin networks (SLIP-44): 0 for mainnet, and 1 for every test network. */
private const val MAINNET_COIN_TYPE = 0
internal const val TESTNET_COIN_TYPE = 1

/**
 * The Bitcoin chain app's manifest (spec 2.1, 11.1). A PSBT is `crypto-psbt`, which the registry names for this app. The response is
 * the signed PSBT, also `crypto-psbt`, and the registry keeps each UR type to one entry, so the response declares none.
 */
val bitcoinManifest: ChainManifest = ChainManifest(
    id = AppId("bitcoin"),
    displayName = "Bitcoin",
    apiVersion = AIRVAULT_API_VERSION,
    appVersion = "1.0.0",
    namespaces = setOf("bip122"),
    networks = listOf(
        bitcoinNetwork(
            chain = ChainId("bip122:000000000019d6689c085ae165831e93"),
            name = "Bitcoin",
            testnet = false,
            coinType = MAINNET_COIN_TYPE,
        ),
        bitcoinNetwork(
            chain = ChainId("bip122:00000000da84f2bafbbc53dee25a72ae"),
            name = "Bitcoin Testnet4",
            testnet = true,
            coinType = TESTNET_COIN_TYPE,
        ),
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
    capabilities = emptySet(),
    requestTypes = listOf(UrTypeSpec("crypto-psbt", Direction.REQUEST, 1..1)),
    responseTypes = emptyList(),
    fields = emptyList(),
)

private fun bitcoinNetwork(chain: ChainId, name: String, testnet: Boolean, coinType: Int) = NetworkSpec(
    chainId = chain,
    name = name,
    testnet = testnet,
    nativeAsset = AssetSpec.Native(
        id = AssetId("${chain.caip2}/slip44:$coinType"),
        chain = chain,
        symbol = "BTC",
        decimals = 8,
    ),
    slip44 = coinType,
)
