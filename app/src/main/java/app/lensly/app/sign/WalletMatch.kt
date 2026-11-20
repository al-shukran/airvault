package app.lensly.app.sign

import app.airvault.chains.bitcoin.bitcoinManifest
import app.airvault.core.model.AppId
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.SignerRef
import app.lensly.chains.installedSignerOf
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
 * The wallets of [wallets] that [request] is for. A Mnemonic wallet is for a request that names its master fingerprint as one of its signers
 * ([namesWallet]). A Single Key wallet has no master key, so it is for a request that its app says is for its address, as the app's
 * isForSingleKey answers.
 */
fun routeScan(request: AppRequest, wallets: List<WalletView>): WalletMatch {
    val app = installedSignerOf(request.header.app)
    return matchesOf(
        wallets.filter { wallet ->
            if (wallet.masterFingerprint != null) {
                request.header.signers.any { namesWallet(request.header.app, it, wallet) }
            } else {
                app?.isForSingleKey(request, wallet.addressAt(0, 0)) == true
            }
        },
    )
}

/** The refusal that the app of [request] makes of it before any wallet is looked for, or null when it makes none. */
fun routingRefusalOf(request: AppRequest): String? = installedSignerOf(request.header.app)?.refusalBeforeRouting(request)

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

/** True when the 20 bytes [address] are the hexadecimal address [expected], in either letter case. */
internal fun sameAddress(address: ByteArray, expected: String): Boolean =
    ("0x" + address.toHex()).equals(expected, ignoreCase = true)
