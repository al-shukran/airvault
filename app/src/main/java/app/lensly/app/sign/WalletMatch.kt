package app.lensly.app.sign

import app.airvault.core.signer.RequestHeader
import app.lensly.core.btc.claimsAccount
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.SolSignRequest
import app.lensly.core.wallet.toHex
import fr.acinq.bitcoin.psbt.Psbt

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
    data class Bitcoin(val psbt: Psbt) : ScannedRequest
    data class Solana(val request: SolSignRequest) : ScannedRequest

    /** A request an installed chain app parsed. [header] names the keys it asks for. */
    data class App(val header: RequestHeader) : ScannedRequest
}

/**
 * The wallets of [wallets] that a scanned [request] is for. An Ethereum request goes by [matchWallet]. A Bitcoin request is for a wallet
 * whose master fingerprint and Bitcoin Account path one of its inputs names. A Solana request, and an app request with one signer, is for
 * a Mnemonic wallet whose master fingerprint it names. A Single Key wallet has no master key, so it is never for a Bitcoin, Solana or app
 * request.
 */
fun routeScan(request: ScannedRequest, wallets: List<WalletView>): WalletMatch = when (request) {
    is ScannedRequest.Ethereum -> matchWallet(request.request, wallets)
    is ScannedRequest.Bitcoin -> matchesOf(
        wallets.filter { wallet ->
            val fingerprint = wallet.masterFingerprint?.toLong()?.and(UINT32_MASK) ?: return@filter false
            wallet.bitcoinAccountPaths.any { claimsAccount(request.psbt, fingerprint, it) }
        },
    )
    is ScannedRequest.Solana -> byFingerprint(request.request.sourceFingerprint, wallets)
    is ScannedRequest.App -> byFingerprint(request.header.signers.singleOrNull()?.fingerprint, wallets)
}

/**
 * The wallets of [wallets] that [request] is for, by the rule [SignPipeline] checks. A Mnemonic wallet is for a request that
 * names its master fingerprint. A Single Key wallet is for a request that names its address, and its fingerprint is not read.
 */
fun matchWallet(request: EthSignRequest, wallets: List<WalletView>): WalletMatch =
    matchesOf(wallets.filter { isFor(request, it) })

private fun byFingerprint(fingerprint: Int?, wallets: List<WalletView>): WalletMatch =
    if (fingerprint == null) WalletMatch.None else matchesOf(wallets.filter { it.masterFingerprint == fingerprint })

private fun matchesOf(matches: List<WalletView>): WalletMatch = when (matches.size) {
    0 -> WalletMatch.None
    1 -> WalletMatch.One(matches.single().id)
    else -> WalletMatch.Several(matches.size)
}

private const val UINT32_MASK = 0xFFFFFFFFL

private fun isFor(request: EthSignRequest, wallet: WalletView): Boolean {
    val fingerprint = wallet.masterFingerprint
    if (fingerprint != null) return request.sourceFingerprint == fingerprint
    val requested = request.address ?: return false
    return sameAddress(requested, wallet.addressAt(0))
}

/** True when the 20 bytes [address] are the hexadecimal address [expected], in either letter case. */
internal fun sameAddress(address: ByteArray, expected: String): Boolean =
    ("0x" + address.toHex()).equals(expected, ignoreCase = true)
