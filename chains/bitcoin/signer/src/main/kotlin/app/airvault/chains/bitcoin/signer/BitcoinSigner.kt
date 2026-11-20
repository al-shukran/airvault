package app.airvault.chains.bitcoin.signer

import app.airvault.chains.bitcoin.BitcoinAddressCodec
import app.airvault.chains.bitcoin.bitcoinManifest
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.Approval
import app.airvault.core.model.ChainId
import app.airvault.core.model.ChainManifest
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.KeyAccess
import app.airvault.core.signer.RequestParser
import app.airvault.core.signer.SignResponse
import app.airvault.core.signer.SignerApp
import app.airvault.core.signer.SignerRef
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.BtcSigner
import app.lensly.core.btc.ScriptType

/**
 * The Lensly side of the Bitcoin chain app: its manifest, its request parser, its address codec and the signing of an approved request.
 *
 * The owner's Bitcoin policies are the signer's own, so a signature is made under the policies its review was made under. An instance built
 * with the core defaults is the one the installed list holds. The one sign flow signs with an instance built from the owner's settings.
 */
/** The levels of an account path m/purpose'/coin'/account'. A request names its accounts at this depth. */
internal const val ACCOUNT_DEPTH = 3

class BitcoinSigner(
    private val inputPolicy: InputVerificationPolicy = InputVerificationPolicy.STRICT,
    private val sourcePolicy: SigningSourcePolicy = SigningSourcePolicy.ANY,
    private val feeThresholds: BtcFeeThresholds = BtcFeeThresholds.DEFAULT,
) : SignerApp {
    override val manifest: ChainManifest = bitcoinManifest

    override val parser: RequestParser = BitcoinRequestParser

    override val addresses: AddressCodec = BitcoinAddressCodec

    /**
     * Signs the approved PSBT of [request] with the keys of the one account its header names, and answers with the signed `crypto-psbt`.
     * The request is read again from its body, and the approval must be for the request's chain. The signer reviews the PSBT against that
     * account, signs the inputs the account owns, and refuses the request as the review does, under this signer's policies. A request that
     * names no account, or more than one, is refused.
     */
    override fun sign(request: AppRequest, approval: Approval, keys: KeyAccess): SignResponse {
        val psbt = BtcPsbt.parse(request.body)
        require(approval.review.chain == request.header.chain) { "The approval is for another chain." }
        val account = accountOf(request.header.signers)
        require(chainIdOf(account.network) == request.header.chain) { ACCOUNT_CHAIN_REFUSED }
        val signer = BtcSigner(account.network, account.scriptType, account.accountIndex, account.fingerprint, keys, inputPolicy, sourcePolicy, feeThresholds)
        val signed = signer.sign(psbt, unverifiedAmountsConfirmed = UNVERIFIED_CHECK in approval.acknowledged)
        return SignResponse(BtcPsbt.UR_TYPE, BtcPsbt.cborOf(signed.psbt))
    }
}

/** The reason for a signer that is not the master fingerprint of an account path m/purpose'/coin'/account' of a Bitcoin script type. */
private const val ACCOUNT_REFUSED = "The request does not name a Bitcoin account of the form m/purpose'/coin'/account'."

/** The reason for an account whose network is not the network of the request's chain. */
private const val ACCOUNT_CHAIN_REFUSED = "The account's network is not the request's chain."

/** The mask of an unsigned 32-bit value, which a master fingerprint is. */
private const val UNSIGNED_INT_MASK = 0xFFFF_FFFFL

/** The account a request names: the master fingerprint of its signer, and the purpose, network and index of its account path. */
private class NamedAccount(val fingerprint: Long, val network: BtcNetwork, val scriptType: ScriptType, val accountIndex: Int)

/** The one account [signers] names. A request names none when no input claims a key, and several when its inputs claim several accounts. */
private fun accountOf(signers: List<SignerRef>): NamedAccount {
    val signer = when (signers.size) {
        0 -> throw BtcRefusedException(BtcRefusal.NothingToSign)
        1 -> signers.single()
        else -> throw BtcRefusedException(BtcRefusal.SeveralAccounts)
    }
    val fingerprint = signer.fingerprint ?: throw IllegalArgumentException(ACCOUNT_REFUSED)
    val path = signer.path
    require(path.size == ACCOUNT_DEPTH && path.all { it.hardened }) { ACCOUNT_REFUSED }
    val scriptType = ScriptType.entries.firstOrNull { it.purpose == path[0].index } ?: throw IllegalArgumentException(ACCOUNT_REFUSED)
    val network = BtcNetwork.entries.firstOrNull { it.coinType == path[1].index } ?: throw IllegalArgumentException(ACCOUNT_REFUSED)
    return NamedAccount(fingerprint.toLong() and UNSIGNED_INT_MASK, network, scriptType, path[2].index)
}

/** The chain ID of [network], as the manifest names it. */
private fun chainIdOf(network: BtcNetwork): ChainId = bitcoinManifest.networks.single { it.slip44 == network.coinType }.chainId
