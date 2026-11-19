package app.airvault.chains.evm

import app.airvault.core.model.AIRVAULT_API_VERSION
import app.airvault.core.model.AppId
import app.airvault.core.model.AssetId
import app.airvault.core.model.AssetSpec
import app.airvault.core.model.ChainId
import app.airvault.core.model.ChainManifest
import app.airvault.core.model.Capability
import app.airvault.core.model.Curve
import app.airvault.core.model.Direction
import app.airvault.core.model.KeyScope
import app.airvault.core.model.NetworkSpec
import app.airvault.core.model.PathTemplate
import app.airvault.core.model.UrTypeSpec

/** Ethereum's SLIP-44 coin type (spec 11.1). Every key path uses it. */
private const val EVM_COIN_TYPE = 60

/** The EVM chain app's manifest (spec 2.1, 11.1), declared once and shared by its signer side. */
val evmManifest: ChainManifest = ChainManifest(
    id = AppId("evm"),
    displayName = "EVM",
    apiVersion = AIRVAULT_API_VERSION,
    appVersion = "1.0.0",
    namespaces = setOf("eip155"),
    networks = listOf(
        evmNetwork(chain = ChainId("eip155:1"), name = "Ethereum", symbol = "ETH", testnet = false),
        evmNetwork(chain = ChainId("eip155:10143"), name = "Monad Testnet", symbol = "MON", testnet = true),
    ),
    keys = listOf(
        KeyScope(
            curve = Curve.SECP256K1,
            pathPrefixes = listOf(PathTemplate("m/44'/$EVM_COIN_TYPE'/*'/0/0")),
            purpose = "EVM account keys",
        ),
    ),
    capabilities = setOf(Capability.MESSAGE_SIGNING, Capability.TYPED_DATA_SIGNING),
    requestTypes = listOf(UrTypeSpec("eth-sign-request", Direction.REQUEST, 1..1)),
    responseTypes = listOf(UrTypeSpec("eth-signature", Direction.RESPONSE, 1..1)),
    fields = emptyList(),
)

private fun evmNetwork(chain: ChainId, name: String, symbol: String, testnet: Boolean) = NetworkSpec(
    chainId = chain,
    name = name,
    testnet = testnet,
    nativeAsset = AssetSpec.Native(
        id = AssetId("${chain.caip2}/slip44:$EVM_COIN_TYPE"),
        chain = chain,
        symbol = symbol,
        decimals = 18,
    ),
    slip44 = EVM_COIN_TYPE,
)
