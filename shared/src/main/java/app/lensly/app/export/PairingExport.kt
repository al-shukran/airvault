package app.lensly.app.export

import app.lensly.core.ur.Cbor
import app.lensly.core.ur.CryptoHdKeyEncoder
import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.framesOf
import app.lensly.core.wallet.AccountAddresses
import app.lensly.core.wallet.AccountXpub
import com.sparrowwallet.hummingbird.UR

/**
 * The pairing export that Rabby and MetaMask import: one ur:crypto-account that lists every Ethereum account of the wallet, in order. The
 * output of account N is the crypto-hdkey of its address key m/44'/60'/N'/0/0, with that full path as its origin and the note
 * [LEDGER_LIVE_NOTE]. Their QR keyring reads such a code as fixed public keys: address N is the address of output N, and its signing path
 * is output N's origin. Those are exactly Lensly's accounts and paths.
 *
 * The keyring lists accounts in pages of [ACCOUNTS_PER_PAGE] and refuses a code whose output count is not a multiple of it, so the
 * caller passes a whole number of pages. A single m/44'/60'/0' key must never be sent instead: the importer would then derive
 * m/44'/60'/0'/0/i, which is Lensly's account only for i = 0. The code carries public material only, never the seed or any private key.
 */
object PairingExport {
    /** The number of accounts the importer lists per page. An export holds a multiple of it. */
    const val ACCOUNTS_PER_PAGE = 5

    /** The note that marks an output as an account on the m/44'/60'/N'/0/0 layout. */
    const val LEDGER_LIVE_NOTE = "account.ledger_live"

    private const val ADDRESS_DEPTH = 5
    private const val UR_TYPE = "crypto-account"
    private const val CRYPTO_HDKEY_TAG = 303L

    /**
     * The pairing parts for [accountKeys], the keys m/44'/60'/N' of accounts 0 until its size, of a wallet whose master fingerprint is
     * [masterFingerprint]. Each key is named [name]. The parts are one QR code when the payload fits, otherwise the parts of an animated
     * one.
     */
    fun urParts(accountKeys: List<AccountXpub>, masterFingerprint: Int, name: String): List<String> {
        require(accountKeys.isNotEmpty() && accountKeys.size % ACCOUNTS_PER_PAGE == 0) {
            "The importer reads whole pages of $ACCOUNTS_PER_PAGE accounts, not ${accountKeys.size}."
        }
        val outputs = accountKeys.mapIndexed { account, accountKey ->
            val key = AccountAddresses.addressXpub(accountKey, 0)
            val hdKey = CryptoHdKeyEncoder.hdKey(
                pubkey33 = key.publicKey,
                chainCode32 = key.chainCode,
                masterFp = masterFingerprint,
                parentFp = key.parentFingerprint,
                name = name,
                origin = CryptoHdKeyEncoder.ACCOUNT_ORIGIN.dropLast(1) + listOf(CryptoHdKeyEncoder.hardened(account), 0, 0),
                depth = ADDRESS_DEPTH,
                note = LEDGER_LIVE_NOTE,
            )
            // A bare crypto-hdkey, with no crypto-output tag and no script expression, is what the importer reads as an account.
            Cbor.Tagged(CRYPTO_HDKEY_TAG, Cbor.decode(hdKey.toUR().cborBytes))
        }
        val account = Cbor.Dict(
            listOf(
                // BCR-2020-015 writes the master fingerprint as a uint32. The importer reads a byte string as 00000000.
                Cbor.Uint(1) to Cbor.Uint(masterFingerprint.toLong() and 0xFFFFFFFFL),
                Cbor.Uint(2) to Cbor.Arr(outputs),
            ),
        )
        return partsOf(framesOf(UR(UR_TYPE, Cbor.encode(account)), DEFAULT_MAX_FRAGMENT))
    }

    /** The account count a pairing exports for a wallet of [accountCount] accounts: the next whole page. */
    fun exportedCount(accountCount: Int): Int = (accountCount + ACCOUNTS_PER_PAGE - 1) / ACCOUNTS_PER_PAGE * ACCOUNTS_PER_PAGE
}
