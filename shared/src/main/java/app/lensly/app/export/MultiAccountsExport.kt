package app.lensly.app.export

import app.lensly.core.btc.BtcWatchOnlyAccount
import app.lensly.core.btc.hdKey
import app.lensly.core.ur.Cbor
import app.lensly.core.ur.CryptoHdKeyEncoder
import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.framesOf
import app.lensly.core.wallet.AccountXpub
import com.sparrowwallet.hummingbird.UR

/**
 * The Keystone multi-accounts export that Keystone Nexus imports: one `ur:crypto-multi-accounts` with the Bitcoin mainnet account keys
 * of BIP44, BIP49, BIP84 and BIP86 on account 0, and the Ethereum account key m/44'/60'/0'. The layout is KeystoneHQ's ur-registry 0.8.1
 * (src/extended/CryptoMultiAccounts.ts): a top-level map, untagged, {1: master fingerprint (uint32), 2: [crypto-hdkey, tag 303],
 * 3: device, 4: device ID, 5: version}. Each key is the crypto-hdkey of the account's public key, chain code, origin keypath with the
 * master fingerprint and depth, and parent fingerprint. It carries public data only, never the seed or a private key.
 */
object MultiAccountsExport {
    /** The UR type that Keystone Nexus reads. */
    const val UR_TYPE = "crypto-multi-accounts"

    /** The device label. Lensly names itself here, so it does not claim to be a Keystone device. */
    const val DEVICE = "Lensly"

    private const val HDKEY_TAG = 303L
    private const val ACCOUNT_DEPTH = 3
    private const val UINT32_MASK = 0xFFFFFFFFL

    /**
     * The frames of the export. A payload that fits one QR code is one frame. A larger one gives the uncoded parts of an animated
     * code, which the encoder emits first, so a caller that cycles this list sends the whole payload in every pass.
     */
    fun urParts(
        masterFingerprint: Int,
        bitcoin: List<BtcWatchOnlyAccount>,
        ethereum: AccountXpub,
        version: String,
    ): List<String> =
        partsOf(framesOf(UR(UR_TYPE, cbor(masterFingerprint, bitcoin, ethereum, version)), DEFAULT_MAX_FRAGMENT))

    /**
     * The CBOR payload of the export. [bitcoin] are the watch-only Accounts of one master fingerprint, and [ethereum] is the account
     * key m/44'/60'/0' of the same seed. The device ID is the master fingerprint as 8 hex digits.
     */
    fun cbor(
        masterFingerprint: Int,
        bitcoin: List<BtcWatchOnlyAccount>,
        ethereum: AccountXpub,
        version: String,
    ): ByteArray {
        require(bitcoin.isNotEmpty()) { "no Bitcoin accounts to export" }
        require(bitcoin.all { it.masterFingerprint == masterFingerprint.toLong() and UINT32_MASK }) {
            "Bitcoin accounts must share the master fingerprint"
        }
        val ethereumKey = CryptoHdKeyEncoder.ur(
            pubkey33 = ethereum.publicKey,
            chainCode32 = ethereum.chainCode,
            masterFp = masterFingerprint,
            parentFp = ethereum.parentFingerprint,
            name = null,
            depth = ACCOUNT_DEPTH,
        )
        val keys = bitcoin.map { hdKeyItem(it.hdKey().toUR().cborBytes) } + hdKeyItem(ethereumKey.cborBytes)
        return Cbor.encode(
            Cbor.Dict(
                listOf(
                    Cbor.Uint(1L) to Cbor.Uint(masterFingerprint.toLong() and UINT32_MASK),
                    Cbor.Uint(2L) to Cbor.Arr(keys),
                    Cbor.Uint(3L) to Cbor.Text(DEVICE),
                    Cbor.Uint(4L) to Cbor.Text("%08x".format(masterFingerprint)),
                    Cbor.Uint(5L) to Cbor.Text(version),
                ),
            ),
        )
    }

    /** One crypto-hdkey map, tagged 303 inside the key array. Lensly's decoder reads the encoded map back as a tree. */
    private fun hdKeyItem(cborBytes: ByteArray): Cbor = Cbor.Tagged(HDKEY_TAG, Cbor.decode(cborBytes))
}
