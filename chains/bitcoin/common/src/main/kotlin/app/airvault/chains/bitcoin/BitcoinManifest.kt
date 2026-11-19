package app.airvault.chains.bitcoin

import app.airvault.core.model.AIRVAULT_API_VERSION
import app.airvault.core.model.AppId
import app.airvault.core.model.AssetId
import app.airvault.core.model.AssetSpec
import app.airvault.core.model.ChainId
import app.airvault.core.model.ChainManifest
import app.airvault.core.model.NetworkSpec

/** Bitcoin mainnet: the first 32 hex characters of its genesis block hash (CAIP-2 bip122, spec 4). */
val bitcoinMainnet = ChainId("bip122:000000000019d6689c085ae165831e93")

/** Bitcoin testnet4: the first 32 hex characters of its genesis block hash (CAIP-2 bip122, spec 4). */
val bitcoinTestnet4 = ChainId("bip122:00000000da84f2bafbbc53dee25a72ae")

/**
 * The Bitcoin chain app's manifest (spec 2.1). Its key scopes, UR types and fields arrive with the Bitcoin signer and wallet
 * sides. Until then it declares the networks and the address rules only.
 */
val bitcoinManifest: ChainManifest = ChainManifest(
    id = AppId("bitcoin"),
    displayName = "Bitcoin",
    apiVersion = AIRVAULT_API_VERSION,
    appVersion = "1.0.0",
    namespaces = setOf("bip122"),
    networks = listOf(
        bitcoinNetwork(chain = bitcoinMainnet, name = "Bitcoin", testnet = false, symbol = "BTC", coinType = 0),
        bitcoinNetwork(chain = bitcoinTestnet4, name = "Bitcoin Testnet4", testnet = true, symbol = "tBTC", coinType = 1),
    ),
    keys = emptyList(),
    capabilities = emptySet(),
    requestTypes = emptyList(),
    responseTypes = emptyList(),
    fields = emptyList(),
)

/** A Bitcoin network. Its coin type is 0 on mainnet and 1 on the test networks, as BIP44 asks. */
private fun bitcoinNetwork(chain: ChainId, name: String, testnet: Boolean, symbol: String, coinType: Int) = NetworkSpec(
    chainId = chain,
    name = name,
    testnet = testnet,
    nativeAsset = AssetSpec.Native(
        id = AssetId("${chain.caip2}/slip44:$coinType"),
        chain = chain,
        symbol = symbol,
        decimals = 8,
    ),
    slip44 = coinType,
)
