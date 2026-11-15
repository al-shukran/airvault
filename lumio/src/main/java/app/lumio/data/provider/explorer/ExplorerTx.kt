package app.lumio.data.provider.explorer

import java.math.BigInteger

/**
 * One row of an EVM explorer's account list. [Normal] is a transaction from the `txlist` action, and [Token] is one ERC-20 transfer from the
 * `tokentx` action. The docs of Etherscan V2, Routescan and Blockscout name no log index in either list, so a [Token] is told apart by its contract,
 * its parties and its amount, and by [Token.ordinal] for identical transfers of one transaction. Addresses are lower case. [to] is null when a
 * transaction creates a contract.
 */
sealed interface ExplorerTx {
    val hash: String
    val timestampSec: Long
    val from: String
    val to: String?
    val confirmations: Long

    /** A transaction that the account sent or received, with the gas it used. [failed] is true when the transaction reverted. */
    data class Normal(
        override val hash: String,
        override val timestampSec: Long,
        override val from: String,
        override val to: String?,
        override val confirmations: Long,
        val valueWei: BigInteger,
        val failed: Boolean,
        val gasUsed: BigInteger,
        val gasPrice: BigInteger,
        /** The input data as lower-case hex. `0x` when there is none, or when the answer's input is not hex. */
        val input: String,
        /** The contract that this transaction created, or null. */
        val createdContract: String?,
    ) : ExplorerTx

    /** One ERC-20 transfer of [amount] base units of [contract]. [ordinal] counts the identical transfers of one transaction, from 0. */
    data class Token(
        override val hash: String,
        override val timestampSec: Long,
        override val from: String,
        override val to: String?,
        override val confirmations: Long,
        val contract: String,
        val name: String,
        val symbol: String,
        val decimals: Int,
        val amount: BigInteger,
        val ordinal: Int = 0,
    ) : ExplorerTx
}
