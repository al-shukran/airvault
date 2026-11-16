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

    /** The plaintext size of the Bitcoin record. A record that holds more is refused when it is written. */
    val BITCOIN_CAPACITY: Int = WalletFolder.BITCOIN_CAPACITY

    /** The wallet's Solana record, 4096 bytes: the public key of each Solana account. */
    val SOLANA: String = WalletFolder.SOLANA

    /** The plaintext size of the Solana record. A record that holds more is refused when it is written. */
    val SOLANA_CAPACITY: Int = WalletFolder.SOLANA_CAPACITY

    /** The wallet's Algorand record, 4096 bytes: the public key of each Algorand account. */
    val ALGORAND: String = WalletFolder.ALGORAND

    /** The plaintext size of the Algorand record. A record that holds more is refused when it is written. */
    val ALGORAND_CAPACITY: Int = WalletFolder.ALGORAND_CAPACITY

    /** The record of account [index], 256 bytes. */
    fun account(index: Int): String = WalletFolder.accountName(index)
}
