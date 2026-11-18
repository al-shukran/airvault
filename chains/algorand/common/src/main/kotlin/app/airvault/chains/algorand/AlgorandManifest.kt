package app.airvault.chains.algorand

import app.airvault.core.model.AIRVAULT_API_VERSION
import app.airvault.core.model.AddressRole
import app.airvault.core.model.AppId
import app.airvault.core.model.AssetId
import app.airvault.core.model.AssetSpec
import app.airvault.core.model.ChainId
import app.airvault.core.model.ChainManifest
import app.airvault.core.model.Curve
import app.airvault.core.model.Danger
import app.airvault.core.model.Direction
import app.airvault.core.model.FieldKey
import app.airvault.core.model.FieldKind
import app.airvault.core.model.FieldSpec
import app.airvault.core.model.KeyScope
import app.airvault.core.model.NetworkSpec
import app.airvault.core.model.PathTemplate
import app.airvault.core.model.UrTypeSpec

/** Algorand's SLIP-44 coin type (spec 11.1). Both networks and every key path use it. */
private const val ALGORAND_COIN_TYPE = 283

/** The Algorand chain app's manifest (spec 2.1, 11.1), declared once and shared by its signer and wallet sides. */
val algorandManifest: ChainManifest = ChainManifest(
    id = AppId("algorand"),
    displayName = "Algorand",
    apiVersion = AIRVAULT_API_VERSION,
    appVersion = "1.0.0",
    namespaces = setOf("algorand"),
    networks = listOf(
        algorandNetwork(
            chain = ChainId("algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k"),
            name = "Algorand",
            testnet = false,
        ),
        algorandNetwork(
            chain = ChainId("algorand:SGO1GKSzyE7IEPItTxCByw9x8FmnrCDe"),
            name = "Algorand Testnet",
            testnet = true,
        ),
    ),
    keys = listOf(
        KeyScope(
            curve = Curve.ED25519_BIP32,
            pathPrefixes = listOf(PathTemplate("m/44'/$ALGORAND_COIN_TYPE'/*'/0/0")),
            purpose = "Algorand account keys",
        ),
    ),
    capabilities = emptySet(),
    requestTypes = listOf(UrTypeSpec("algo-sign-request", Direction.REQUEST, 1..1)),
    responseTypes = listOf(UrTypeSpec("algo-signature", Direction.RESPONSE, 1..1)),
    fields = listOf(
        FieldSpec(FieldKey("algorand.note"), FieldKind.MemoBytes(maxBytes = 1024), label = "Note"),
        FieldSpec(
            key = FieldKey("algorand.closeTo"),
            kind = FieldKind.AddressField(AddressRole.CLOSE_TO),
            label = "Close To",
            danger = Danger.DANGER,
        ),
        FieldSpec(
            key = FieldKey("algorand.rekeyTo"),
            kind = FieldKind.AddressField(AddressRole.REKEY_TO),
            label = "Rekey To",
            danger = Danger.DANGER,
        ),
        FieldSpec(
            key = FieldKey("algorand.lease"),
            kind = FieldKind.MemoBytes(maxBytes = null, fixedLength = 32),
            label = "Lease",
            danger = Danger.CAUTION,
        ),
    ),
)

private fun algorandNetwork(chain: ChainId, name: String, testnet: Boolean) = NetworkSpec(
    chainId = chain,
    name = name,
    testnet = testnet,
    nativeAsset = AssetSpec.Native(
        id = AssetId("${chain.caip2}/slip44:$ALGORAND_COIN_TYPE"),
        chain = chain,
        symbol = "ALGO",
        decimals = 6,
    ),
    slip44 = ALGORAND_COIN_TYPE,
)
