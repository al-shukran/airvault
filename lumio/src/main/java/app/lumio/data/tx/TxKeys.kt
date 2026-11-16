package app.lumio.data.tx

import app.lensly.core.vault.VaultSession
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The keys of the transaction store, all derived from the open vault's session. [macKey] names every row, term and comment by a keyed hash, so the
 * database holds no transaction id, account, address or word in the clear. [encKey] seals each row's payload and each comment. [exportKey] seals the
 * comment export. Each is a separate HKDF purpose, so one key never serves two jobs.
 */
class TxKeys(val macKey: ByteArray, val encKey: ByteArray, val exportKey: ByteArray) {
    /** The keyed name of [value] under [label]. The label keeps a name of one kind from ever equalling a name of another kind. */
    fun name(label: String, value: String): ByteArray {
        val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(macKey, HMAC)) }
        return mac.doFinal("$label\u0000$value".toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val HMAC = "HmacSHA256"

        /** The keys of [session], which hold while the vault is open. */
        fun from(session: VaultSession): TxKeys = TxKeys(
            macKey = session.deriveKey("lumio-tx-v1/mac"),
            encKey = session.deriveKey("lumio-tx-v1/enc"),
            exportKey = session.deriveKey("lumio-tx-comments-export-v1"),
        )
    }
}
