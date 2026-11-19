package app.airvault.chains.evm

import app.airvault.core.model.AIRVAULT_API_VERSION
import app.airvault.core.model.AppId
import app.airvault.core.model.AssetId
import app.airvault.core.model.AssetSpec
import app.airvault.core.model.ChainId
import app.airvault.core.model.ChainManifest
import app.airvault.core.model.NetworkSpec

/** Ethereum mainnet. One EVM address names the same account on every EVM network, so this stands for the family (spec 4). */
val ethereumMainnet = ChainId("eip155:1")

/**
 * The EVM chain app's manifest (spec 2.1). Its networks, key scopes, UR types and fields arrive with the EVM signer and wallet
 * sides. Until then it declares Ethereum mainnet and the address rules only.
 */
val evmManifest: ChainManifest = ChainManifest(
    id = AppId("evm"),
    displayName = "Ethereum and EVM networks",
    apiVersion = AIRVAULT_API_VERSION,
    appVersion = "1.0.0",
    namespaces = setOf("eip155"),
    networks = listOf(
        NetworkSpec(
            chainId = ethereumMainnet,
            name = "Ethereum",
            testnet = false,
            nativeAsset = AssetSpec.Native(
                id = AssetId("${ethereumMainnet.caip2}/slip44:60"),
                chain = ethereumMainnet,
                symbol = "ETH",
                decimals = 18,
            ),
            slip44 = 60,
        ),
    ),
    keys = emptyList(),
    capabilities = emptySet(),
    requestTypes = emptyList(),
    responseTypes = emptyList(),
    fields = emptyList(),
)
