package app.lensly.app

import app.lensly.core.wallet.AccountXpub
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * The records Lensly wrote before wallet kinds existed: meta version 1 and secret version 1. They are written here by
 * hand, from the layout in [WalletStore], and not by the production encoder. A change to that encoder therefore cannot
 * change what these tests read, and the tests keep reading the records that vaults on a real phone already hold.
 */
internal object LegacyWalletRecords {

    /** A version 1 meta record: no kind byte, so it is a Mnemonic wallet. */
    fun meta(
        name: String,
        createdAtMs: Long,
        deviceGenerated: Boolean,
        accountCount: Int,
        masterFingerprintHex: String,
        xpub: AccountXpub,
    ): ByteArray = bytes { out ->
        out.writeByte(1)
        out.writeUTF(name)
        out.writeLong(createdAtMs)
        out.writeBoolean(deviceGenerated)
        out.writeInt(accountCount)
        out.writeUTF(masterFingerprintHex)
        out.writeInt(xpub.publicKey.size)
        out.write(xpub.publicKey)
        out.writeInt(xpub.chainCode.size)
        out.write(xpub.chainCode)
        out.writeInt(xpub.parentFingerprint)
    }

    /** A version 1 secret record: the words and the optional passphrase. */
    fun secret(words: List<String>, passphrase: String?): ByteArray = bytes { out ->
        out.writeByte(1)
        out.writeByte(words.size)
        words.forEach { out.writeUTF(it) }
        out.writeBoolean(passphrase != null)
        if (passphrase != null) out.writeUTF(passphrase)
    }

    /** An account's name record as renaming wrote it before profiles: version 1, then the Display Name. */
    fun accountName(name: String): ByteArray = bytes { out ->
        out.writeByte(1)
        out.writeUTF(name)
    }

    private inline fun bytes(write: (DataOutputStream) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use(write)
        return buffer.toByteArray()
    }
}
