package app.lensly.ui.sign

import app.airvault.chains.bitcoin.signer.BtcReview
import app.airvault.chains.bitcoin.signer.bitcoinManifest
import app.airvault.chains.bitcoin.signer.toReview
import app.airvault.core.model.ChainId
import app.airvault.core.model.NetworkLabel
import app.airvault.core.model.Review
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType

/** The chain of a Bitcoin network, as the Bitcoin manifest names it. */
internal fun bitcoinChainId(network: BtcNetwork): ChainId =
    bitcoinManifest.networks.single { it.testnet == (network == BtcNetwork.TESTNET4) }.chainId

/**
 * The review the owner reads for a Bitcoin request on [network] from an account of [scriptType]. [nameOf] gives an output address its
 * Address Book name for the summary sentence. The app adds the names and the Android words; the core builds the rest.
 */
internal fun bitcoinReviewOf(
    review: BtcReview,
    network: BtcNetwork,
    scriptType: ScriptType,
    nameOf: (String) -> String? = { null },
): Review = review.toReview(
    chain = bitcoinChainId(network),
    network = NetworkLabel(name = bitcoinNetworkName(network), testnet = network == BtcNetwork.TESTNET4, detail = null),
    scriptType = scriptTypeName(scriptType),
    nameOf = nameOf,
)

/** The Script Type a review shows, with its address kind. */
internal fun scriptTypeName(scriptType: ScriptType): String = when (scriptType) {
    ScriptType.LEGACY -> "Legacy P2PKH"
    ScriptType.NESTED_SEGWIT -> "Nested Segwit P2SH-P2WPKH"
    ScriptType.NATIVE_SEGWIT -> "Native Segwit P2WPKH"
    ScriptType.TAPROOT -> "Taproot P2TR"
}
