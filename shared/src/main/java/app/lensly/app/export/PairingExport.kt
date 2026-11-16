package app.lensly.app.export

import app.lensly.core.wallet.AccountXpub

/**
 * The pairing export that Rabby and MetaMask import: ur:crypto-multi-accounts with one Ledger Live key for each Ethereum account, the
 * Ethereum App's keys (see [EthereumNexusKeys]). Account N's key is m/44'/60'/N' with the children 0/0
 * that reach its address. It carries public material only, never the seed or any private key.
 */
object PairingExport {
    /**
     * Pairing parts for the account keys [accountKeys] (m/44'/60'/N' for account N, in order) of a wallet whose master fingerprint is
     * [masterFingerprint]: one QR code when the payload fits, otherwise the parts of an animated one. Each key is named [name], and the
     * export names the app [version].
     */
    fun urParts(accountKeys: List<AccountXpub>, masterFingerprint: Int, version: String, name: String): List<String> =
        MultiAccountsExport.urParts(masterFingerprint, EthereumNexusKeys.ledgerLiveKeys(accountKeys, masterFingerprint, name), version)
}
