package app.lensly.app.export

import app.lensly.core.ur.CryptoHdKeyEncoder
import app.lensly.core.wallet.AccountXpub

/**
 * The pairing export that Rabby and MetaMask import: one ur:crypto-hdkey, the BIP44 Ethereum account key m/44'/60'/0' of account 0. Rabby's QR
 * sync accepts only crypto-hdkey and crypto-account, and it rejects crypto-multi-accounts, so that layout is not written here. Rabby derives its
 * address i as m/44'/60'/0'/0/i. Lensly's account 0 is m/44'/60'/0'/0/0, which is the importer's first address. The code carries public material
 * only, never the seed or any private key.
 */
object PairingExport {
    /** The depth of the account key m/44'/60'/0'. */
    private const val ACCOUNT_KEY_DEPTH = 3

    /** Larger than any code this export writes, so the code is one QR code and never an animated part. */
    private const val ONE_CODE = 1024

    /**
     * The pairing code of the first of [accountKeys], which is the key m/44'/60'/0' of account 0, for a wallet whose master fingerprint is
     * [masterFingerprint]. The key is named [name].
     */
    fun urParts(accountKeys: List<AccountXpub>, masterFingerprint: Int, name: String): List<String> {
        val accountKey = accountKeys.first()
        val code = CryptoHdKeyEncoder.urParts(
            pubkey33 = accountKey.publicKey,
            chainCode32 = accountKey.chainCode,
            masterFp = masterFingerprint,
            parentFp = accountKey.parentFingerprint,
            name = name,
            maxFragment = ONE_CODE,
            depth = ACCOUNT_KEY_DEPTH,
        ).first()
        return listOf(code)
    }
}
