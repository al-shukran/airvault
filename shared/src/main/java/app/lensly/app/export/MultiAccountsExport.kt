package app.lensly.app.export

import app.lensly.core.ur.Cbor
import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.framesOf
import com.sparrowwallet.hummingbird.UR
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The Keystone multi-accounts export that Keystone Nexus imports: one `ur:crypto-multi-accounts` whose account keys are the
 * [NexusKeys] of the wallet's Apps. The layout is KeystoneHQ's ur-registry 0.8.1 (src/extended/CryptoMultiAccounts.ts): a top-level map,
 * untagged, {1: master fingerprint (uint32), 2: [crypto-hdkey, tag 303], 3: device, 4: device ID, 5: version}. Each key is a
 * crypto-hdkey of an account's public key, chain code, origin keypath with the master fingerprint and depth, and parent fingerprint.
 * It carries public data only, never the seed or a private key.
 */
object MultiAccountsExport {
    /** The UR type that Keystone Nexus reads. */
    const val UR_TYPE = "crypto-multi-accounts"

    /** The device label. Lensly names itself here, so it does not claim to be a Keystone device. */
    const val DEVICE = "Lensly"

    private const val HDKEY_TAG = 303L
    private const val UINT32_MASK = 0xFFFFFFFFL
    private const val DEVICE_ID_BYTES = 20
    private val DEVICE_ID_TAG = "lensly-device".toByteArray(Charsets.US_ASCII)

    /**
     * The frames of the export for [keys], the crypto-hdkey items of every App (see [cbor]). A payload that fits one QR code is one
     * frame. A larger one gives the uncoded parts of an animated code, which the encoder emits first, so a caller that cycles this
     * list sends the whole payload in every pass.
     */
    fun urParts(masterFingerprint: Int, keys: List<Cbor>, version: String): List<String> =
        partsOf(framesOf(UR(UR_TYPE, cbor(masterFingerprint, keys, version)), DEFAULT_MAX_FRAGMENT))

    /**
     * The CBOR payload of the export: the master fingerprint, the [keys] in the order given, the device label, the device ID and
     * [version]. The device ID is [deviceId] of the master fingerprint.
     */
    fun cbor(masterFingerprint: Int, keys: List<Cbor>, version: String): ByteArray {
        require(keys.isNotEmpty()) { "no account keys to export" }
        return Cbor.encode(
            Cbor.Dict(
                listOf(
                    Cbor.Uint(1L) to Cbor.Uint(masterFingerprint.toLong() and UINT32_MASK),
                    Cbor.Uint(2L) to Cbor.Arr(keys),
                    Cbor.Uint(3L) to Cbor.Text(DEVICE),
                    Cbor.Uint(4L) to Cbor.Text(deviceId(masterFingerprint)),
                    Cbor.Uint(5L) to Cbor.Text(version),
                ),
            ),
        )
    }

    /**
     * The device ID of the export: the first 20 bytes of SHA-256 over the ASCII tag "lensly-device" followed by the master fingerprint as
     * 4 big-endian bytes, written as 40 lowercase hex digits. Keystone's device writes a 40-digit device ID too, derived from its serial.
     */
    fun deviceId(masterFingerprint: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(DEVICE_ID_TAG + ByteBuffer.allocate(4).putInt(masterFingerprint).array())
        return digest.copyOf(DEVICE_ID_BYTES).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** One crypto-hdkey as a key of the export: its encoded map, tagged 303 inside the key array. */
    fun hdKeyItem(cborBytes: ByteArray): Cbor = Cbor.Tagged(HDKEY_TAG, Cbor.decode(cborBytes))
}
