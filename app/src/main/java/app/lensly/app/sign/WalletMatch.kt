package app.lensly.app.sign

import app.airvault.chains.bitcoin.bitcoinManifest
import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.core.model.AppId
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.SignerRef
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.toHex

/** Which wallets of a vault a request is for. */
sealed interface WalletMatch {
    /** Exactly one wallet is for the request. [walletId] is its record id. */
    data class One(val walletId: String) : WalletMatch

    /** No wallet of the vault is for the request. */
    data object None : WalletMatch

    /** [count] wallets are for the request. Lensly does not choose between them. */
    data class Several(val count: Int) : WalletMatch
}

/**
 * A complete scanned request, read by the Parser Sandbox. Its UR type names the chain, and the fields it names pick the wallet.
 */
sealed interface ScannedRequest {
    data class Ethereum(val request: EthSignRequest) : ScannedRequest

    /** A request an installed chain app parsed. [header] names the keys it asks for, one signer per key. */
    data class App(val header: RequestHeader) : ScannedRequest
}

/**
 * The wallets of [wallets] that a scanned [request] is for. An Ethereum request goes by [matchWallet]. An app request is for each wallet that one
 * of its signers names, as [namesWallet] says. A Single Key wallet has no master key, so it is never for an app request.
 */
fun routeScan(request: ScannedRequest, wallets: List<WalletView>): WalletMatch = when (request) {
    is ScannedRequest.Ethereum -> matchWallet(request.request, wallets)
    is ScannedRequest.App -> matchesOf(
        wallets.filter { wallet -> request.header.signers.any { namesWallet(request.header.app, it, wallet) } },
    )
}

/**
 * The wallets of [wallets] that [request] is for, by the rule [SignPipeline] checks. A Mnemonic wallet is for a request that
 * names its master fingerprint. A Single Key wallet is for a request that names its address, and its fingerprint is not read.
 */
fun matchWallet(request: EthSignRequest, wallets: List<WalletView>): WalletMatch =
    matchesOf(wallets.filter { isFor(request, it) })

/**
 * True when [signer], a key an app request asks for, is one of [wallet]'s keys: the wallet's master fingerprint is the signer's. A Bitcoin request
 * names an account path too, so it is for a wallet only when the wallet stores that Bitcoin Account. A request from an account the wallet does not
 * hold is for no wallet.
 */
private fun namesWallet(app: AppId, signer: SignerRef, wallet: WalletView): Boolean {
    val fingerprint = wallet.masterFingerprint ?: return false
    if (signer.fingerprint != fingerprint) return false
    return app != bitcoinManifest.id || wallet.bitcoinAccountPaths.any { it.path == accountChildren(signer.path) }
}

/** The child numbers of the account path m/purpose'/coin'/account' that [path] starts with, as a KeyPath holds them: a hardened level sets bit 31. */
private fun accountChildren(path: List<PathStep>): List<Long> = path.take(ACCOUNT_PATH_LEVELS).map { step ->
    step.index.toLong() + if (step.hardened) HARDENED_BIT else 0L
}

/** The levels of an account path, m/purpose'/coin'/account'. */
private const val ACCOUNT_PATH_LEVELS = 3

/** The bit that marks a hardened child number. */
private const val HARDENED_BIT = 0x8000_0000L

private fun matchesOf(matches: List<WalletView>): WalletMatch = when (matches.size) {
    0 -> WalletMatch.None
    1 -> WalletMatch.One(matches.single().id)
    else -> WalletMatch.Several(matches.size)
}

private fun isFor(request: EthSignRequest, wallet: WalletView): Boolean {
    val fingerprint = wallet.masterFingerprint
    if (fingerprint != null) return request.sourceFingerprint == fingerprint
    val requested = request.address ?: return false
    return sameAddress(requested, wallet.addressAt(0, 0))
}

/** True when the 20 bytes [address] are the hexadecimal address [expected], in either letter case. */
internal fun sameAddress(address: ByteArray, expected: String): Boolean =
    ("0x" + address.toHex()).equals(expected, ignoreCase = true)
