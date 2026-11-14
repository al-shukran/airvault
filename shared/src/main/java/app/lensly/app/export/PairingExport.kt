package app.lensly.app.export

import app.lensly.core.ur.CryptoHdKeyEncoder
import app.lensly.core.wallet.AccountXpub
import app.lensly.core.wallet.HdWallet

/**
 * The pairing export that Rabby and MetaMask import: ur:crypto-hdkey for the account key m/44'/60'/0'.
 * It carries public material only (the account public key, its chain code and two fingerprints), never the seed
 * or any private key.
 */
object PairingExport {
    private const val DEFAULT_NAME = "Lensly"

    /** Pairing parts for [wallet]: one QR code when the payload fits, otherwise the parts of an animated one. */
    fun urParts(wallet: HdWallet, name: String = DEFAULT_NAME): List<String> =
        urParts(wallet.accountXpub(), wallet.masterFingerprint, name)

    /** As the wallet overload, for a caller that holds the account xpub and the master fingerprint. */
    fun urParts(xpub: AccountXpub, masterFingerprint: Int, name: String = DEFAULT_NAME): List<String> =
        partsOf(
            CryptoHdKeyEncoder.urParts(
                pubkey33 = xpub.publicKey,
                chainCode32 = xpub.chainCode,
                masterFp = masterFingerprint,
                parentFp = xpub.parentFingerprint,
                name = name,
            ),
        )
}
