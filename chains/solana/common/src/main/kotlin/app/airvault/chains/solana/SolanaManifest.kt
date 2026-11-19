package app.airvault.chains.solana

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

/** Solana's SLIP-44 coin type (spec 11.1). Every network and every key path use it. */
const val SOLANA_COIN_TYPE = 501

// The CAIP-2 references of the three clusters (the ChainAgnostic Solana namespace).
val SOLANA_MAINNET: ChainId = ChainId("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp")
val SOLANA_DEVNET: ChainId = ChainId("solana:EtWTRABZaYq6iMfeYKouRu166VU2xqa1")
val SOLANA_TESTNET: ChainId = ChainId("solana:4uhcVJyU9pJkvQyS88uRDiswHXSCkY3z")

/**
 * The Solana chain app's manifest (spec 2.1, 11.1), declared once and shared by its signer side. The CAIP-2 references are the
 * registry values. A request names its cluster, and one that names none is for mainnet-beta.
 */
val solanaManifest: ChainManifest = ChainManifest(
    id = AppId("solana"),
    displayName = "Solana",
    apiVersion = AIRVAULT_API_VERSION,
    appVersion = "1.0.0",
    namespaces = setOf("solana"),
    networks = listOf(
        solanaNetwork(chain = SOLANA_MAINNET, name = "Solana", testnet = false, primary = true),
        solanaNetwork(chain = SOLANA_DEVNET, name = "Solana Devnet", testnet = true, primary = true),
        solanaNetwork(chain = SOLANA_TESTNET, name = "Solana Testnet", testnet = true),
    ),
    keys = listOf(
        KeyScope(
            curve = Curve.ED25519_SLIP10,
            pathPrefixes = listOf(PathTemplate("m/44'/$SOLANA_COIN_TYPE'/*'")),
            purpose = "Solana account keys",
        ),
    ),
    // Solana signs off-chain messages, such as a sign-in text, as well as transactions.
    capabilities = setOf(Capability.MESSAGE_SIGNING),
    requestTypes = listOf(UrTypeSpec("sol-sign-request", Direction.REQUEST, 1..1)),
    responseTypes = listOf(UrTypeSpec("sol-signature", Direction.RESPONSE, 1..1)),
    fields = emptyList(),
)

private fun solanaNetwork(chain: ChainId, name: String, testnet: Boolean, primary: Boolean = false) = NetworkSpec(
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
    primary = primary,
)
