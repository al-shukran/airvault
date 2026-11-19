package app.airvault.chains.solana

import app.airvault.core.model.AIRVAULT_API_VERSION
import app.airvault.core.model.AppId
import app.airvault.core.model.AssetId
import app.airvault.core.model.AssetSpec
import app.airvault.core.model.ChainId
import app.airvault.core.model.ChainManifest
import app.airvault.core.model.NetworkSpec

/** Solana mainnet: the first 32 characters of its genesis hash, in base58 (CAIP-2 solana, spec 4). */
val solanaMainnet = ChainId("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp")

/** Solana devnet: the first 32 characters of its genesis hash, in base58 (CAIP-2 solana, spec 4). */
val solanaDevnet = ChainId("solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1")

/** Solana's SLIP-44 coin type. */
private const val SOLANA_COIN_TYPE = 501

/**
 * The Solana chain app's manifest (spec 2.1). Its key scopes, UR types and fields arrive with the Solana signer and wallet sides.
 * Until then it declares the networks and the address rules only.
 */
val solanaManifest: ChainManifest = ChainManifest(
    id = AppId("solana"),
    displayName = "Solana",
    apiVersion = AIRVAULT_API_VERSION,
    appVersion = "1.0.0",
    namespaces = setOf("solana"),
    networks = listOf(
        solanaNetwork(chain = solanaMainnet, name = "Solana", testnet = false),
        solanaNetwork(chain = solanaDevnet, name = "Solana Devnet", testnet = true),
    ),
    keys = emptyList(),
    capabilities = emptySet(),
    requestTypes = emptyList(),
    responseTypes = emptyList(),
    fields = emptyList(),
)

private fun solanaNetwork(chain: ChainId, name: String, testnet: Boolean) = NetworkSpec(
    chainId = chain,
    name = name,
    testnet = testnet,
    nativeAsset = AssetSpec.Native(
        id = AssetId("${chain.caip2}/slip44:$SOLANA_COIN_TYPE"),
        chain = chain,
        symbol = "SOL",
        decimals = 9,
    ),
    slip44 = SOLANA_COIN_TYPE,
)
