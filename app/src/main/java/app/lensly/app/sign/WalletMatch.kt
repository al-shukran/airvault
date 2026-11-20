package app.lensly.app.sign

import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.SignerApp
import app.airvault.core.signer.SignerRef
import app.lensly.chains.installedSignerOf
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
                request.header.signers.any { namesWallet(app, it, wallet) }
            } else {
                app?.isForSingleKey(request, wallet.addressAt(0, 0)) == true
            }
        },
    )
}

/** The refusal that the app of [request] makes of it before any wallet is looked for, or null when it makes none. */
fun routingRefusalOf(request: AppRequest): String? = installedSignerOf(request.header.app)?.refusalBeforeRouting(request)

/**
 * True when [signer], a key an app request asks for, is one of [wallet]'s keys: the wallet's master fingerprint is the signer's, and the app says
 * the wallet holds the account the signer names. A request from an account the wallet does not hold is for no wallet. A request of an app that
 * is not installed names no account, so the fingerprint alone decides for it.
 */
private fun namesWallet(app: SignerApp?, signer: SignerRef, wallet: WalletView): Boolean {
    val fingerprint = wallet.masterFingerprint ?: return false
    if (signer.fingerprint != fingerprint) return false
    return app?.namesStoredAccount(signer, wallet.storedAccountPaths) ?: true
}

private fun matchesOf(matches: List<WalletView>): WalletMatch = when (matches.size) {
    0 -> WalletMatch.None
    1 -> WalletMatch.One(matches.single().id)
    else -> WalletMatch.Several(matches.size)
}

/** True when the 20 bytes [address] are the hexadecimal address [expected], in either letter case. */
internal fun sameAddress(address: ByteArray, expected: String): Boolean =
    ("0x" + address.toHex()).equals(expected, ignoreCase = true)
