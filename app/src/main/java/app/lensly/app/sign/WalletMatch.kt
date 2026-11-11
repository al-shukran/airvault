package app.lensly.app.sign

import app.lensly.core.ur.EthSignRequest
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
 * The wallets of [wallets] that [request] is for, by the rule [SignPipeline] checks. A Mnemonic wallet is for a request that
 * names its master fingerprint. A Single Key wallet is for a request that names its address, and its fingerprint is not read.
 */
fun matchWallet(request: EthSignRequest, wallets: List<WalletView>): WalletMatch {
    val matches = wallets.filter { isFor(request, it) }
    return when (matches.size) {
        0 -> WalletMatch.None
        1 -> WalletMatch.One(matches.single().id)
        else -> WalletMatch.Several(matches.size)
    }
}

private fun isFor(request: EthSignRequest, wallet: WalletView): Boolean {
    val fingerprint = wallet.masterFingerprint
    if (fingerprint != null) return request.sourceFingerprint == fingerprint
    val requested = request.address ?: return false
    return sameAddress(requested, wallet.addressAt(0))
}

/** True when the 20 bytes [address] are the hexadecimal address [expected], in either letter case. */
internal fun sameAddress(address: ByteArray, expected: String): Boolean =
    ("0x" + address.toHex()).equals(expected, ignoreCase = true)
