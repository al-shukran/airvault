package app.lensly.core.vault

/**
 * The names of the records a wallet folder holds, for an app that keeps its own records in one. [VaultSession.putRecord] refuses any other
 * name. Each record has a fixed capacity: META holds 256 bytes, BITCOIN 8192 and each account record 256.
 */
object WalletRecordNames {
    /** The wallet's metadata record, 256 bytes. */
    val META: String = WalletFolder.META

    /** The wallet's Bitcoin record, 8192 bytes. */
    val BITCOIN: String = WalletFolder.BITCOIN

    /** The record of account [index], 256 bytes. */
    fun account(index: Int): String = WalletFolder.accountName(index)
}
